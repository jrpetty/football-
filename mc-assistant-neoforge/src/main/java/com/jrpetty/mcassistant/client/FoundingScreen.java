package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.net.FoundingChoicePayload;
import com.jrpetty.mcassistant.net.FoundingScreenPayload;
import com.jrpetty.mcassistant.village.FoundingPlan;
import com.jrpetty.mcassistant.village.VillageMath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Found a village: how many folk are to start it, asked at the board a Village Folk Spawner put
 * up (entity/Founding), starting at seventy (villageCharterFolk). A slider from two to five hundred (or
 * to what this server lets a village be founded with, and it says so when that is less), a step down and up, the usual sizes to pick at a
 * click, and under them what that many means: how much ground is levelled for them, the trades
 * they settle into, the homes they will want, and what so many cost a server. Nothing happens
 * until Confirm and spawn, and the server checks that again.
 */
public class FoundingScreen extends Screen {

    private static final int W = 320, H = 240, PAD = 10;
    private static final int[] PRESETS = { 2, 12, 25, 50, 70, 100, 250, 500 };

    private final BlockPos board;
    private final int most, worldFolk, worldCap;
    private int count;
    private int left, top, h = H;
    private CountSlider slider;

    public FoundingScreen(FoundingScreenPayload p) {
        super(Component.literal("Found a village"));
        this.board = p.board();
        this.most = Math.max(FoundingPlan.MIN_FOLK, Math.min(FoundingPlan.MAX_FOLK, p.most()));
        this.worldFolk = p.worldFolk();
        this.worldCap = p.worldCap();
        this.count = clamp(p.start());
    }

    /** The server's word: open the screen for a waiting board, or close it once the board is answered. */
    public static void show(FoundingScreenPayload p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!p.open()) {
            if (mc.screen instanceof FoundingScreen s && s.board.equals(p.board())) s.onClose();
            return;
        }
        mc.setScreen(new FoundingScreen(p));
    }

    private int clamp(int n) {
        return Math.max(FoundingPlan.MIN_FOLK, Math.min(most, n));
    }

    // ------------------------------------------------------------------ the slider, on a lean

    /** Two to five hundred evenly by ratio, so two to twelve is not squeezed into the first pixel. */
    private double toSlider(int n) {
        if (most <= FoundingPlan.MIN_FOLK) return 0;
        return (Math.log(n) - Math.log(FoundingPlan.MIN_FOLK)) / (Math.log(most) - Math.log(FoundingPlan.MIN_FOLK));
    }

    private int fromSlider(double v) {
        double n = Math.exp(Math.log(FoundingPlan.MIN_FOLK) + v * (Math.log(most) - Math.log(FoundingPlan.MIN_FOLK)));
        return clamp((int) Math.round(n));
    }

    private final class CountSlider extends AbstractSliderButton {
        CountSlider(int x, int y, int w, int h) {
            super(x, y, w, h, Component.empty(), toSlider(count));
            updateMessage();
        }

        /** [screens13] In the house look: a recessed track, filled to the handle in the accent, a raised handle. */
        @Override
        public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            UiStyle.well(g, x, y, w, h, UiStyle.WELL);
            int hx = x + (int) (this.value * (w - 8));
            g.fill(x + 1, y + 1, hx + 4, y + h - 1, UiStyle.alpha(UiStyle.ACCENT_FILL, 0.45F));
            UiStyle.tile(g, hx, y, 8, h, UiStyle.State.of(true, false, isHoveredOrFocused()));
            String label = getMessage().getString();
            g.drawString(Minecraft.getInstance().font, label, x + (w - Minecraft.getInstance().font.width(label)) / 2, y + (h - 8) / 2, UiStyle.TEXT, false);
        }

        void set(int n) {
            this.value = toSlider(n);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(count + " folk"));
        }

        @Override
        protected void applyValue() {
            count = fromSlider(this.value);
        }
    }

    private void setCount(int n) {
        count = clamp(n);
        if (slider != null) slider.set(count);
    }

    // ------------------------------------------------------------------ laying it out

    @Override
    protected void init() {
        this.h = Math.min(H, this.height - 8);                              // [screens13] fits a 240-high screen with its ring
        this.left = (this.width - W) / 2;
        this.top = (this.height - h) / 2;
        int x = left + PAD, inner = W - PAD * 2;
        int y = top + 36;
        Button less = Button.builder(Component.literal("−"), b -> setCount(count - (hasShiftDown() ? 10 : 1)))
            .bounds(x, y, 20, 20).build(UiButton::new);
        less.setTooltip(Tooltip.create(Component.literal("One fewer (with Shift, ten)")));
        this.addRenderableWidget(less);
        slider = new CountSlider(x + 24, y, inner - 48, 20);
        this.addRenderableWidget(slider);
        Button more = Button.builder(Component.literal("+"), b -> setCount(count + (hasShiftDown() ? 10 : 1)))
            .bounds(x + inner - 20, y, 20, 20).build(UiButton::new);
        more.setTooltip(Tooltip.create(Component.literal("One more (with Shift, ten)")));
        this.addRenderableWidget(more);
        int gap = 2, bw = (inner - gap * (PRESETS.length - 1)) / PRESETS.length;
        for (int i = 0; i < PRESETS.length; i++) {
            int n = PRESETS[i];
            Button b = Button.builder(Component.literal(String.valueOf(n)), btn -> setCount(n))
                .bounds(x + i * (bw + gap), y + 23, bw, 16).build(b2 -> new Preset(b2, n));
            b.active = n <= most;
            b.setTooltip(Tooltip.create(Component.literal(n <= most ? "Start with " + n + " folk"
                : "More than this server lets a village be founded with (" + most + ")")));
            this.addRenderableWidget(b);
        }
        int by = top + h - PAD - 20;
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> this.onClose())
            .bounds(left + W - PAD - 100 - 4 - 120, by, 100, 20).build(UiButton::new));
        Button go = UiButton.primary(Button.builder(Component.literal("Confirm and spawn"), b -> {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(new FoundingChoicePayload(board, count));
            this.onClose();
        }).bounds(left + W - PAD - 120, by, 120, 20));
        go.setTooltip(Tooltip.create(Component.literal("The ground is made level first; the folk come as soon as the heart of it is.")));
        this.addRenderableWidget(go);
    }

    /** Everything this screen paints itself, between the background and the widgets (see NameScreen for why). */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        UiStyle.shadow(g, left, top, W, h);                                 // [screens13] the house panel and header band
        UiStyle.panel(g, left, top, W, h);
        UiStyle.headerBand(g, left, top, W, 20);
        int x = left + PAD, inner = W - PAD * 2;
        UiStyle.title(g, this.font, this.title.getString(), x, top + 7);
        Ui.right(g, this.font, most < FoundingPlan.MAX_FOLK ? "this server: up to " + most : "2 to " + most,
            left + W - PAD, top + 7, most < FoundingPlan.MAX_FOLK ? UiStyle.WARNING : UiStyle.TEXT_FAINT);
        UiStyle.heading(g, this.font, "How many folk start the village", x, top + 25, inner);

        int y = top + 80;
        if (most < FoundingPlan.MAX_FOLK) {
            g.drawString(this.font, Ui.clip(this.font, "This server lets a village be founded with up to " + most
                + " (villageFoundingMost).", inner), x, y, UiStyle.WARNING, false);
            y += 11;
        }
        y += 2;
        UiStyle.heading(g, this.font, "What that means", x, y, inner);
        y += 12;
        UiStyle.well(g, x - 2, y - 2, inner + 4, top + h - PAD - 24 - (y - 2), UiStyle.PAGE);   // [screens13] what it means, on a page
        int radius = FoundingPlan.coreRadius(count);
        int across = 2 * radius + 1;
        y = line(g, "Ground", "about " + across + " by " + across + " blocks made level round the board, the edges"
            + " sloped into the land.", x, y, inner, UiStyle.TEXT);
        int kinds = FoundingPlan.tradeKinds(count);
        y = line(g, "Trades", kinds + (kinds == 1 ? " kind" : " kinds") + " of work: "
            + FoundingPlan.trades(count, 3) + ".", x, y, inner, UiStyle.TEXT);
        int homes = VillageMath.housesWanted(count, true);
        int camp = Math.min(count, com.jrpetty.mcassistant.VillageSpawner.campRoom());
        y = line(g, "Homes", homes + (homes == 1 ? " house" : " houses") + " wanted; a bed each at the camp"
            + (count > camp ? " for " + camp + ", the rest's bedding in the stores for the first houses." : "."),
            x, y, inner, UiStyle.TEXT);
        double ms = FoundingPlan.msPerTick(count);
        int load = ms >= 20 ? UiStyle.DANGER : ms >= 6 ? UiStyle.WARNING : UiStyle.TEXT;
        y = line(g, "Server", String.format(java.util.Locale.ROOT, "about %.1f ms of every 50 ms tick", ms)
            + (count >= 150 ? ". Hundreds of folk are heavy for a server." : count >= 60 ? ": a busy town." : "."),
            x, y, inner, load);
        if (worldFolk + count > worldCap) {
            line(g, "World", worldFolk + " folk live in the world and it may hold " + worldCap
                + ": past that, no children.", x, y, inner, UiStyle.WARNING);
        }
    }

    /** "Label: words", wrapped, the label in the panel's ink. Returns where the next goes. */
    private int line(GuiGraphics g, String label, String words, int x, int y, int w, int colour) {
        String head = label + ": ";
        g.drawString(this.font, head, x + 2, y, UiStyle.TEXT_FAINT, false);
        int hw = this.font.width(head);
        var lines = TextCache.split(this.font, words, w - hw - 4);
        for (int i = 0; i < lines.size() && i < 2; i++) {
            g.drawString(this.font, lines.get(i), x + 2 + hw, y, colour, false);
            y += this.font.lineHeight + 1;
        }
        return y + 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** [screens13] A usual size: chosen when it is the count now. */
    private final class Preset extends Button {
        private final int n;

        Preset(Button.Builder b, int n) {
            super(b);
            this.n = n;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            UiStyle.button(g, Minecraft.getInstance().font, getMessage().getString(), getX(), getY(), getWidth(), getHeight(),
                UiStyle.State.of(this.active, count == n, isHoveredOrFocused()));
        }
    }
}
