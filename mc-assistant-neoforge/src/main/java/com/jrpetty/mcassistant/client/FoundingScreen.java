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
    private int left, top;
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
        this.left = (this.width - W) / 2;
        this.top = (this.height - H) / 2;
        int x = left + PAD, inner = W - PAD * 2;
        int y = top + 36;
        Button less = Button.builder(Component.literal("−"), b -> setCount(count - (hasShiftDown() ? 10 : 1)))
            .bounds(x, y, 20, 20).build();
        less.setTooltip(Tooltip.create(Component.literal("One fewer (with Shift, ten)")));
        this.addRenderableWidget(less);
        slider = new CountSlider(x + 24, y, inner - 48, 20);
        this.addRenderableWidget(slider);
        Button more = Button.builder(Component.literal("+"), b -> setCount(count + (hasShiftDown() ? 10 : 1)))
            .bounds(x + inner - 20, y, 20, 20).build();
        more.setTooltip(Tooltip.create(Component.literal("One more (with Shift, ten)")));
        this.addRenderableWidget(more);
        int gap = 2, bw = (inner - gap * (PRESETS.length - 1)) / PRESETS.length;
        for (int i = 0; i < PRESETS.length; i++) {
            int n = PRESETS[i];
            Button b = Button.builder(Component.literal(String.valueOf(n)), btn -> setCount(n))
                .bounds(x + i * (bw + gap), y + 23, bw, 16).build();
            b.active = n <= most;
            b.setTooltip(Tooltip.create(Component.literal(n <= most ? "Start with " + n + " folk"
                : "More than this server lets a village be founded with (" + most + ")")));
            this.addRenderableWidget(b);
        }
        int by = top + H - PAD - 20;
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> this.onClose())
            .bounds(left + W - PAD - 100 - 4 - 120, by, 100, 20).build());
        Button go = Button.builder(Component.literal("Confirm and spawn"), b -> {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(new FoundingChoicePayload(board, count));
            this.onClose();
        }).bounds(left + W - PAD - 120, by, 120, 20).build();
        go.setTooltip(Tooltip.create(Component.literal("The ground is made level first; the folk come as soon as the heart of it is.")));
        this.addRenderableWidget(go);
    }

    /** Everything this screen paints itself, between the background and the widgets (see NameScreen for why). */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        Ui.panel(g, left, top, W, H, 22);
        int x = left + PAD, inner = W - PAD * 2;
        g.drawString(this.font, this.title, x, top + 7, Ui.INK, false);
        Ui.right(g, this.font, most < FoundingPlan.MAX_FOLK ? "this server: up to " + most : "2 to " + most,
            left + W - PAD, top + 7, most < FoundingPlan.MAX_FOLK ? Ui.WARN : Ui.MUTED);
        Ui.section(g, this.font, "How many folk start the village", x, top + 26, inner);

        int y = top + 80;
        if (most < FoundingPlan.MAX_FOLK) {
            g.drawString(this.font, Ui.clip(this.font, "This server lets a village be founded with up to " + most
                + " (villageFoundingMost).", inner), x, y, Ui.WARN, false);
            y += 11;
        }
        y += 2;
        Ui.section(g, this.font, "What that means", x, y, inner);
        y += 12;
        int radius = FoundingPlan.coreRadius(count);
        int across = 2 * radius + 1;
        y = line(g, "Ground", "about " + across + " by " + across + " blocks made level round the board, the edges"
            + " sloped into the land.", x, y, inner, Ui.INK);
        int kinds = FoundingPlan.tradeKinds(count);
        y = line(g, "Trades", kinds + (kinds == 1 ? " kind" : " kinds") + " of work: "
            + FoundingPlan.trades(count, 3) + ".", x, y, inner, Ui.INK);
        int homes = VillageMath.housesWanted(count, true);
        int camp = Math.min(count, com.jrpetty.mcassistant.VillageSpawner.campRoom());
        y = line(g, "Homes", homes + (homes == 1 ? " house" : " houses") + " wanted; a bed each at the camp"
            + (count > camp ? " for " + camp + ", the rest's bedding in the stores for the first houses." : "."),
            x, y, inner, Ui.INK);
        double ms = FoundingPlan.msPerTick(count);
        int load = ms >= 20 ? Ui.BAD : ms >= 6 ? Ui.WARN : Ui.INK;
        y = line(g, "Server", String.format(java.util.Locale.ROOT, "about %.1f ms of every 50 ms tick", ms)
            + (count >= 150 ? ". Hundreds of folk are heavy for a server." : count >= 60 ? ": a busy town." : "."),
            x, y, inner, load);
        if (worldFolk + count > worldCap) {
            line(g, "World", worldFolk + " folk live in the world and it may hold " + worldCap
                + ": past that, no children.", x, y, inner, Ui.WARN);
        }
    }

    /** "Label: words", wrapped, the label in the panel's ink. Returns where the next goes. */
    private int line(GuiGraphics g, String label, String words, int x, int y, int w, int colour) {
        String head = label + ": ";
        g.drawString(this.font, head, x, y, Ui.MUTED, false);
        int hw = this.font.width(head);
        var lines = TextCache.split(this.font, words, w - hw);
        for (int i = 0; i < lines.size() && i < 2; i++) {
            g.drawString(this.font, lines.get(i), x + hw, y, colour, false);
            y += this.font.lineHeight + 1;
        }
        return y + 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
