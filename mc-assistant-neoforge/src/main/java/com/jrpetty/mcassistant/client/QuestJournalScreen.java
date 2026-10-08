package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.net.QuestActionPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * [quests] The Quest Journal, open: the player's quests, those under way on one tab and those done (or given up, or
 * failed) on the other, a list down the left; on the right the one chosen, as a page: its title, who gave it and in
 * which town, the reward, the day it is due; its steps, each a line (a story's as chapters, only those it has come to),
 * done ones ticked, with what came of each; for the step it is on, where to go and which way from where the player
 * stands now, worked out afresh each frame, and how far; and a story's ending page once it is over. "Give up" asks
 * twice. The server writes the pages (entity/QuestRun.journal).
 */
public class QuestJournalScreen extends Screen {

    private static final int PAD = 8, HEADER = 34, TAB_H = 16, ROW = 22, LINE = 10, LIST_W = 136;
    private static final int BG = 0xF0181B22, BAND = 0xFF20242E, EDGE = 0xFF8A7A50, SOFT = 0xFF3A3F4C, GOLD = 0xFFE8C46A, INK = 0xFFEDEDED,
        MUTED = 0xFF9AA3B2, DONE = 0xFF86C98A, BAD = 0xFFE08B8B, TAB = 0xFF2A2F3A, TAB_ON = 0xFF51441F, PICK = 0xFF2F3A4A, LOG = 0x80000000,
        WHERE = 0xFF9EC3EA;

    private record StepRow(int status, String chapter, String text, String x, String y, String z, String dim, String radius, String note, String who) {}

    private static final class Entry {
        int id;
        String state = "", kind = "", title = "", giver = "", town = "", reward = "", outcome = "", offer = "", ending = "";
        long deadline;
        boolean story;
        final List<StepRow> steps = new ArrayList<>();

        boolean open() { return state.equals("ACTIVE"); }
    }

    private final List<Entry> entries = new ArrayList<>();
    private String player = "", titles = "";
    private long today;
    private boolean doneTab;
    private int selected, listScroll, pageScroll, pageMax;
    private int confirm = -1;
    private int w, h, left, top;
    private Button giveUp;

    public QuestJournalScreen(String text) {
        super(Component.literal("Quest Journal"));
        read(text);
    }

    public void update(String text) {
        int was = selectedId();
        read(text);
        List<Entry> shown = shown();
        for (int i = 0; i < shown.size(); i++) if (shown.get(i).id == was) selected = i;
        confirm = -1;
        rebuildWidgets();
    }

    private void read(String text) {
        entries.clear();
        Entry e = null;
        for (String line : text.split("\n")) {
            String[] f = line.split("\u001f", -1);
            try {
                switch (f[0]) {
                    case "H" -> {
                        player = f[1];
                        titles = f[2];
                        today = Long.parseLong(f[3]);
                    }
                    case "Q" -> {
                        e = new Entry();
                        e.id = Integer.parseInt(f[1]);
                        e.state = f[2];
                        e.kind = f[3];
                        e.title = f[4];
                        e.giver = f[5];
                        e.town = f[6];
                        e.reward = f[7];
                        e.deadline = Long.parseLong(f[8]);
                        e.outcome = f[9];
                        e.story = "1".equals(f[10]);
                        e.offer = f.length > 11 ? f[11] : "";
                        entries.add(e);
                    }
                    case "S" -> {
                        if (e != null) e.steps.add(new StepRow(Integer.parseInt(f[1]), f[2], f[3], f[4], f[5], f[6], f[7], f[8], f[9], f.length > 10 ? f[10] : ""));
                    }
                    case "E" -> {
                        if (e != null) e.ending = f[1];
                    }
                    default -> { }
                }
            } catch (RuntimeException ignored) {
                // a line from a newer server than this journal knows
            }
        }
        if (shown().isEmpty() && !doneTab) doneTab = !entries.isEmpty();
    }

    private List<Entry> shown() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (e.open() != doneTab) out.add(e);
        return out;
    }

    private int selectedId() {
        List<Entry> s = shown();
        return selected >= 0 && selected < s.size() ? s.get(selected).id : -1;
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        w = Math.min(440, width - 12);
        h = Math.min(300, height - 8);
        left = (width - w) / 2;
        top = (height - h) / 2;
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(left + w - PAD - 56, top + h - PAD - 18, 56, 18).build());
        giveUp = Button.builder(Component.literal("Give up"), b -> giveUp()).bounds(left + w - PAD - 56 - 4 - 90, top + h - PAD - 18, 90, 18).build();
        addRenderableWidget(giveUp);
        refreshGiveUp();
    }

    private void refreshGiveUp() {
        if (giveUp == null) return;
        List<Entry> s = shown();
        Entry e = selected >= 0 && selected < s.size() ? s.get(selected) : null;
        giveUp.visible = e != null && e.open();
        giveUp.setMessage(Component.literal(e != null && confirm == e.id ? "Sure? Give up" : "Give up"));
    }

    private void giveUp() {
        int id = selectedId();
        if (id < 0) return;
        if (confirm != id) {
            confirm = id;
            refreshGiveUp();
            return;
        }
        confirm = -1;
        PacketDistributor.sendToServer(new QuestActionPayload(1, id));
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int ty = top + HEADER + 2;
        String[] labels = tabLabels();
        int x = left + PAD;
        for (int i = 0; i < 2; i++) {
            int tw = font.width(labels[i]) + 14;
            if (mx >= x && mx < x + tw && my >= ty && my < ty + TAB_H) {
                if (doneTab != (i == 1)) {
                    doneTab = i == 1;
                    selected = 0;
                    listScroll = 0;
                    pageScroll = 0;
                    confirm = -1;
                    refreshGiveUp();
                }
                return true;
            }
            x += tw + 2;
        }
        int lx = left + PAD, ly = listTop();
        if (mx >= lx && mx < lx + LIST_W && my >= ly && my < listBottom()) {
            int i = (int) ((my - ly) / ROW) + listScroll;
            if (i >= 0 && i < shown().size()) {
                selected = i;
                pageScroll = 0;
                confirm = -1;
                refreshGiveUp();
            }
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (mx < left + PAD + LIST_W) {
            int most = Math.max(0, shown().size() - (listBottom() - listTop()) / ROW);
            listScroll = Math.max(0, Math.min(most, listScroll - (int) Math.signum(sy)));
        } else {
            pageScroll = Math.max(0, Math.min(pageMax, pageScroll - 12 * (int) Math.signum(sy)));
        }
        return true;
    }

    private String[] tabLabels() {
        int open = 0, done = 0;
        for (Entry e : entries) if (e.open()) open++; else done++;
        return new String[]{ "Under way (" + open + ")", "Done (" + done + ")" };
    }

    private int listTop() {
        return top + HEADER + TAB_H + 6;
    }

    private int listBottom() {
        return top + h - PAD - 24;
    }

    // ------------------------------------------------------------------ painting

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(left, top, left + w, top + h, BG);
        g.fill(left, top, left + w, top + HEADER, BAND);
        g.fill(left, top, left + w, top + 1, EDGE);
        g.fill(left, top + h - 1, left + w, top + h, EDGE);
        g.fill(left, top, left + 1, top + h, EDGE);
        g.fill(left + w - 1, top, left + w, top + h, EDGE);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawString(font, "Quest Journal", left + PAD, top + 7, GOLD, true);
        String who = player.isEmpty() ? "" : player;
        g.drawString(font, who, left + w - PAD - font.width(who), top + 7, MUTED, false);
        g.drawString(font, Ui.clip(font, titles.isEmpty() ? "No titles yet: do the towns a good turn or two." : titles, w - 2 * PAD), left + PAD, top + 20,
            titles.isEmpty() ? MUTED : INK, false);
        // The tabs.
        String[] labels = tabLabels();
        int x = left + PAD, ty = top + HEADER + 2;
        for (int i = 0; i < 2; i++) {
            int tw = font.width(labels[i]) + 14;
            boolean on = doneTab == (i == 1);
            g.fill(x, ty, x + tw, ty + TAB_H, on ? TAB_ON : TAB);
            if (on) g.fill(x, ty + TAB_H - 2, x + tw, ty + TAB_H, GOLD);
            g.drawString(font, labels[i], x + 7, ty + 4, on ? GOLD : INK, false);
            x += tw + 2;
        }
        list(g, mouseX, mouseY);
        page(g);
    }

    private void list(GuiGraphics g, int mouseX, int mouseY) {
        int x0 = left + PAD, y0 = listTop(), y1 = listBottom();
        g.fill(x0, y0, x0 + LIST_W, y1, LOG);
        List<Entry> s = shown();
        if (s.isEmpty()) {
            for (FormattedCharSequence l : TextCache.splitPlain(font, doneTab ? "Nothing done yet." : "No quests under way. Look for a gold \"!\" over a folk's head, and ask it \"Any work for me?\"", LIST_W - 8)) {
                g.drawString(font, l, x0 + 4, y0 + 4, MUTED, false);
                y0 += LINE;
            }
            return;
        }
        int rows = (y1 - y0) / ROW;
        for (int i = 0; i < rows && listScroll + i < s.size(); i++) {
            Entry e = s.get(listScroll + i);
            int y = y0 + i * ROW;
            boolean pick = listScroll + i == selected;
            boolean hover = mouseX >= x0 && mouseX < x0 + LIST_W && mouseY >= y && mouseY < y + ROW;
            if (pick || hover) g.fill(x0, y, x0 + LIST_W, y + ROW - 1, pick ? PICK : 0x40FFFFFF);
            if (pick) g.fill(x0, y, x0 + 2, y + ROW - 1, GOLD);
            int ink = e.state.equals("DONE") ? DONE : e.state.equals("ACTIVE") ? (e.story ? GOLD : INK) : BAD;
            String mark = e.state.equals("DONE") ? "✓ " : e.state.equals("ACTIVE") ? (e.story ? "★ " : "") : "✗ ";
            g.drawString(font, Ui.clip(font, mark + e.title, LIST_W - 8), x0 + 5, y + 2, ink, false);
            String sub = e.kind + " · " + e.town;
            g.drawString(font, Ui.clip(font, sub, LIST_W - 8), x0 + 5, y + 11, MUTED, false);
        }
    }

    private void page(GuiGraphics g) {
        int x0 = left + PAD + LIST_W + 6, x1 = left + w - PAD, y0 = listTop(), y1 = listBottom();
        g.fill(x0, y0, x1, y1, LOG);
        List<Entry> s = shown();
        if (selected < 0 || selected >= s.size()) return;
        Entry e = s.get(selected);
        int inner = x1 - x0 - 10, cx = x0 + 5;
        g.enableScissor(x0, y0, x1, y1);
        int y = y0 + 4 - pageScroll;
        y = wrap(g, e.title, cx, y, inner, GOLD);
        y = wrap(g, (e.story ? "A story · " : e.kind + " · ") + "from " + e.giver + " of " + e.town, cx, y, inner, MUTED);
        y = wrap(g, "Reward: " + e.reward, cx, y, inner, INK);
        if (e.open()) {
            long daysLeft = e.deadline - today;
            y = wrap(g, "Due: day " + (e.deadline + 1) + (daysLeft <= 0 ? " — today!" : daysLeft == 1 ? " — tomorrow" : " — in " + daysLeft + " days"), cx, y, inner,
                daysLeft <= 0 ? BAD : INK);
        } else {
            y = wrap(g, QuestTitleCase.state(e.state) + (e.outcome.isEmpty() ? "" : ": " + e.outcome), cx, y, inner, e.state.equals("DONE") ? DONE : BAD);
        }
        if (!e.offer.isEmpty()) y = wrap(g, "\"" + e.offer + "\"", cx, y + 2, inner, 0xFFB8B0A0);
        y += 4;
        y = heading(g, e.story ? "Chapters" : "Steps", cx, y, inner);
        String chapter = "";
        for (StepRow r : e.steps) {
            if (e.story && !r.chapter().isEmpty() && !r.chapter().equals(chapter)) {
                chapter = r.chapter();
                y = wrap(g, chapter, cx, y + 1, inner, GOLD);
            }
            String tick = r.status() == 0 ? "✓ " : r.status() == 1 ? "▶ " : "· ";
            int ink = r.status() == 0 ? DONE : r.status() == 1 ? INK : MUTED;
            y = wrap(g, tick + r.text(), cx + (e.story ? 6 : 0), y, inner - (e.story ? 6 : 0), ink);
            if (!r.note().isEmpty()) y = wrap(g, r.note(), cx + 16, y, inner - 16, MUTED);
            if (r.status() == 1 && e.open() && !r.x().isEmpty()) y = wrap(g, where(r), cx + 16, y, inner - 16, WHERE);
            else if (r.status() == 1 && e.open() && !r.who().isEmpty()) y = wrap(g, "Find " + r.who() + ".", cx + 16, y, inner - 16, WHERE);
        }
        if (!e.ending.isEmpty()) {
            y += 4;
            y = heading(g, "Ending", cx, y, inner);
            y = wrap(g, e.ending, cx, y, inner, 0xFFE0D6B8);
        }
        g.disableScissor();
        int content = y + pageScroll - y0 + 4;
        pageMax = Math.max(0, content - (y1 - y0));
        if (pageScroll > pageMax) pageScroll = pageMax;
        if (pageMax > 0) {
            g.drawString(font, pageScroll > 0 ? "▲" : " ", x1 - 9, y0 + 2, MUTED, false);
            g.drawString(font, pageScroll < pageMax ? "▼" : " ", x1 - 9, y1 - 10, MUTED, false);
        }
    }

    /** Where a step is, and which way from where the player stands now: "x y z — 120 blocks north-east, 30 down". */
    private String where(StepRow r) {
        if (minecraft == null || minecraft.player == null) return "";
        int x, y, z;
        try {
            x = Integer.parseInt(r.x());
            y = Integer.parseInt(r.y());
            z = Integer.parseInt(r.z());
        } catch (NumberFormatException e) {
            return "";
        }
        String here = minecraft.player.level().dimension().location().toString();
        String at = x + " " + y + " " + z;
        if (!r.dim().isEmpty() && !r.dim().equals(here)) return "Where: " + at + " (in " + r.dim().replaceFirst("^minecraft:", "").replace('_', ' ') + ")";
        double dx = x + 0.5 - minecraft.player.getX(), dz = z + 0.5 - minecraft.player.getZ();
        int far = (int) Math.sqrt(dx * dx + dz * dz);
        int dy = y - (int) Math.floor(minecraft.player.getY());
        if (far <= Math.max(3, parse(r.radius()))) return "Where: " + at + " — here" + (Math.abs(dy) > 4 ? ", " + Math.abs(dy) + (dy < 0 ? " down" : " up") : "") + ".";
        return "Where: " + at + " — " + far + " blocks " + way(dx, dz) + (Math.abs(dy) > 6 ? ", " + Math.abs(dy) + (dy < 0 ? " down" : " up") : "") + ".";
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String way(double dx, double dz) {
        double a = Math.toDegrees(Math.atan2(dx, -dz));
        String[] w = { "north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west" };
        return w[(int) Math.floorMod(Math.round(a / 45.0), 8L)];
    }

    private int heading(GuiGraphics g, String text, int x, int y, int width) {
        g.drawString(font, text, x, y, GOLD, false);
        int rx = x + font.width(text) + 6;
        g.fill(rx, y + 4, x + width, y + 5, SOFT);
        return y + LINE + 3;
    }

    private int wrap(GuiGraphics g, String text, int x, int y, int width, int colour) {
        for (FormattedCharSequence l : TextCache.splitPlain(font, text, Math.max(20, width))) {
            g.drawString(font, l, x, y, colour, false);
            y += LINE;
        }
        return y + 1;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** The finished states, in words. */
    private static final class QuestTitleCase {
        static String state(String s) {
            return switch (s) {
                case "DONE" -> "Done";
                case "FAILED" -> "Failed";
                case "ABANDONED" -> "Given up";
                default -> s.toLowerCase(Locale.ROOT);
            };
        }
    }
}
