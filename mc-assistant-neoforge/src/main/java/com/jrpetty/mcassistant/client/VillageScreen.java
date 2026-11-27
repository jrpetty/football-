package com.jrpetty.mcassistant.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

import static com.jrpetty.mcassistant.client.UiStyle.*;

/**
 * The village journal (J): everything /village status says about the village you stand in,
 * on a page instead of one long chat line — a line to each part (the trades, the stores, the
 * beds, the watch, the elder's orders, what it is short of), the part's name in bold.
 *
 * <p>[screens13] In the house look, laid out to the window: the village's name in the header band with its pages as
 * tabs under it (the village, its wages, its economy, and the town's books, which open their own screen), the page in a
 * recessed well with a scroll bar (the wheel, the arrow keys, Page Up and Down), and the foot: the pace of time, the
 * one going now marked (the same as the [ ] \ keys), and Close. A list the server wrote raw ("[]") reads as words.
 */
public class VillageScreen extends Screen {

    private static final int HEAD = 22, TABS = 14, FOOT = 22;
    private static final String[] PAGES = {"Village", "Wages", "Economy", "Books ›"};
    private static final int[] CODES = {0, 2, 3, 4};
    private static final int[] SPEEDS = {1, 2, 4, 8, 16, 32, 64, com.jrpetty.mcassistant.TimeSpeed.MAX};

    /** The page last asked for, so its tab shows chosen when the answer opens the screen. */
    private static int asked = 0;

    private final String text;
    private final List<FormattedCharSequence> lines = new ArrayList<>();
    private final int page;
    private int left, top, w, h, scroll;

    public VillageScreen(String title, String text) {
        super(Component.literal(title));
        this.text = text;
        this.page = asked;
    }

    /** The server's answer to the journal key. */
    public static void show(String title, String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.setScreen(new VillageScreen(title, text));
    }

    /** Ask the server for the page (the J key). */
    public static void request() {
        request(0);
    }

    /** 0 the village, 2 its wages (who is paid what, best paid first), 3 its economy (what it makes, earns and is worth). */
    public static void request(int page) {
        if (page != 4) asked = page;
        Waiting.start(page == 4 ? "Opening the town's books" : "Opening the journal");   // [ui29]
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.jrpetty.mcassistant.net.VillageAskPayload(page));
    }

    private int bodyTop() {
        return top + HEAD + TABS + S1;
    }

    private int bodyBottom() {
        return top + h - FOOT - S1;
    }

    @Override
    protected void init() {
        this.w = Math.min(380, this.width - S4);
        this.h = Math.min(262, this.height - S4);
        this.left = (this.width - w) / 2;
        this.top = (this.height - h) / 2;
        lines.clear();
        int wrap = w - 2 * S2 - 2 * S1 - 6;
        if (text.contains("\n")) {
            // A list page (the wages, the economy): a line to each entry, as the server wrote it.
            for (String part : text.split("\n", -1)) {
                String p = part.trim();
                if (p.isEmpty()) { lines.add(FormattedCharSequence.EMPTY); continue; }
                int colon = p.indexOf(": ");
                Component c = colon > 0 && colon < 40
                    ? Component.empty()
                        .append(Component.literal(p.substring(0, colon + 1)).withStyle(net.minecraft.ChatFormatting.BOLD))
                        .append(Component.literal(p.substring(colon + 1)))
                    : Component.literal(p);
                lines.addAll(this.font.split(c, wrap));
            }
        } else {
            // "Label: value. Label: value." — a paragraph to each part, its label in bold.
            for (String part : text.split("\\. (?=[A-Z][A-Za-z' ]{1,24}: )|\\. (?=Village at )")) {
                String p = words(part.trim());
                if (p.isEmpty()) continue;
                int colon = p.indexOf(": ");
                Component c = colon > 0 && colon < 26
                    ? Component.empty()
                        .append(Component.literal(p.substring(0, colon + 1)).withStyle(net.minecraft.ChatFormatting.BOLD))
                        .append(Component.literal(p.substring(colon + 1)))
                    : Component.literal(p);
                lines.addAll(this.font.split(c, wrap));
                lines.add(FormattedCharSequence.EMPTY);
            }
            if (!lines.isEmpty()) lines.remove(lines.size() - 1);
        }
        scroll = Math.max(0, Math.min(maxScroll(), scroll));
        // The pages, as tabs under the header.
        int tx = left + S2;
        for (int i = 0; i < PAGES.length; i++) {
            int code = CODES[i];
            int tw = font.width(PAGES[i]) + 14;
            Button b = Button.builder(Component.literal(PAGES[i]), btn -> request(code)).bounds(tx, top + HEAD + 1, tw, TABS - 1)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(switch (code) {
                    case 2 -> "Who is paid what, best paid first, and the town's pay scale";
                    case 3 -> "What the village makes, sells and is worth";
                    case 4 -> "The town's books: growth, money, jobs, folk, the leader, homes, stores and what drives it, with charts";
                    default -> "The village: its trades, stores, homes and plans";
                }))).build(b2 -> new Tab(b2, code == page));
            this.addRenderableWidget(b);
            tx += tw + 2;
        }
        // [purse20] Your own finances, a tab beside the town's.
        int fw = font.width("Finances ›") + 14;
        this.addRenderableWidget(Button.builder(Component.literal("Finances ›"), btn -> FinanceClient.openFrom(this)).bounds(tx, top + HEAD + 1, fw, TABS - 1)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Your finances: what you have, earn, spend and owe")))
            .build(b2 -> new Tab(b2, false)));
        // The foot: the pace of time, then Close.
        int fy = top + h - FOOT + 3;
        int closeW = 50;
        this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose())
            .bounds(left + w - S2 - closeW, fy, closeW, 16).build(UiButton::new));
        int timeX = left + S2 + font.width("Time") + S2;
        int room = left + w - S2 - closeW - S2 - timeX;
        int bw = Math.max(18, Math.min(30, (room - (SPEEDS.length - 1) * 2) / SPEEDS.length));
        for (int i = 0; i < SPEEDS.length; i++) {
            int f = SPEEDS[i];
            Button b = Button.builder(Component.literal(com.jrpetty.mcassistant.TimeSpeed.label(f)), btn -> TimeSpeedClient.ask(0, f))
                .bounds(timeX + i * (bw + 2), fy, bw, 16)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(f == 1
                    ? "Time at its normal pace"
                    : f >= com.jrpetty.mcassistant.TimeSpeed.MAX ? "Time as fast as this machine can run it"
                    : "Time runs " + f + " times faster, to watch the village grow"))).build(b2 -> new Speed(b2, f));
            this.addRenderableWidget(b);
        }
    }

    /** A raw list as the server writes it ("Built: []", "[a, b]") in words. */
    private static String words(String p) {
        return p.replace(": []", ": nothing yet").replaceAll("\\[([^\\[\\]]*)\\]", "$1");
    }

    private int visible() {
        return Math.max(1, (bodyBottom() - bodyTop() - 2 * S1) / (this.font.lineHeight + 1));
    }

    private int maxScroll() {
        return Math.max(0, lines.size() - visible());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(dy) * 3));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        int step = switch (key) {
            case 264 -> 1;                    // down
            case 265 -> -1;                   // up
            case 267 -> visible() - 1;        // page down
            case 266 -> -(visible() - 1);     // page up
            default -> 0;
        };
        if (step != 0) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll + step));
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        shadow(g, left, top, w, h);
        panel(g, left, top, w, h);
        headerBand(g, left, top, w, HEAD + TABS - 1);
        String count = lines.size() > visible() ? (scroll + 1) + "–" + Math.min(lines.size(), scroll + visible()) + " of " + lines.size() : "";
        int cw = font.width(count);
        title(g, font, clip(font, this.title.getString(), w - 2 * S2 - cw - S2), left + S2, top + 7);
        if (!count.isEmpty()) g.drawString(font, count, left + w - S2 - cw, top + 7, TEXT_FAINT, false);
        // The page, in its well.
        int bx = left + S2, by = bodyTop(), bw = w - 2 * S2, bh = bodyBottom() - by;
        well(g, bx, by, bw, bh, PAGE);
        int y = by + S1;
        for (int i = scroll; i < Math.min(lines.size(), scroll + visible()); i++) {
            g.drawString(this.font, lines.get(i), bx + S1 + 1, y, TEXT, false);
            y += this.font.lineHeight + 1;
        }
        if (lines.isEmpty()) empty(g, font, "Nothing written here yet", "Walk into a village and press J again.", bx, by, bw, bh);
        scrollBar(g, bx + bw - 5, by + 2, bh - 4, scroll, maxScroll(), visible());
        // The foot: a rule, and the word before the pace of time.
        int fy = top + h - FOOT;
        g.fill(left + 1, fy, left + w - 1, fy + 1, EDGE_SOFT);
        g.fill(left + 1, fy + 1, left + w - 1, fy + 2, HI);
        g.drawString(this.font, "Time", left + S2, fy + 7, TimeSpeedClient.factor() > 1 ? ACCENT : TEXT_MUTED, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** A page's tab: chosen when it is the page showing. */
    private static final class Tab extends Button {
        private final boolean chosen;

        Tab(Button.Builder b, boolean chosen) {
            super(b);
            this.chosen = chosen;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            var font = Minecraft.getInstance().font;
            topTab(g, getX(), getY(), getWidth(), getHeight(), State.of(true, chosen, isHoveredOrFocused()));
            String label = getMessage().getString();
            g.drawString(font, label, getX() + (getWidth() - font.width(label)) / 2, getY() + 3, chosen ? TEXT : TEXT_MUTED, false);
        }
    }

    /** A pace of time: chosen when it is the pace now. */
    private static final class Speed extends Button {
        private final int factor;

        Speed(Button.Builder b, int factor) {
            super(b);
            this.factor = factor;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            var font = Minecraft.getInstance().font;
            boolean now = TimeSpeedClient.factor() == factor || factor >= com.jrpetty.mcassistant.TimeSpeed.MAX && TimeSpeedClient.factor() >= factor;
            button(g, font, getMessage().getString(), getX(), getY(), getWidth(), getHeight(), State.of(true, now, isHoveredOrFocused()));
        }
    }
}
