package com.jrpetty.mcassistant.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * [found23] The welcome card, once a founding is confirmed: what happens next (the ground levelled, the folk coming, the
 * first buildings) and the next steps, each a button: find the elder (it glows), the town's books, follow the elder as a
 * favourite, your life in town. The steps answer once the first of the folk have come.
 */
public class WelcomeScreen extends Screen {

    private static final int W = 300, H = 244, PAD = 10;
    private final CompoundTag data;
    private int left, top, h = H;

    public WelcomeScreen(CompoundTag t) {
        super(Component.literal(t.getString("name") + " is on its way"));
        this.data = t;
    }

    @Override
    protected void init() {
        this.h = Math.min(H, this.height - 8);
        this.left = (this.width - W) / 2;
        this.top = (this.height - h) / 2;
        int inner = W - PAD * 2, bw = (inner - 6) / 2;
        int y = top + h - PAD - 20 - 44;
        step("Find the elder", "elder", "Whoever leads them glows for half a minute, and you are told where.", left + PAD, y, bw);
        step("The town's books", "books", "Its people, trades, stores and plans.", left + PAD + bw + 6, y, bw);
        step("Follow the elder", "favourite", "A favourite: you hear how they get on.", left + PAD, y + 22, bw);
        step("Your life in town", "life", "Your page in the town (the U key): work, a home, citizenship.", left + PAD + bw + 6, y + 22, bw);
        this.addRenderableWidget(UiButton.primary(Button.builder(Component.literal("Off you go"), b -> this.onClose())
            .bounds(left + W - PAD - 100, top + h - PAD - 20, 100, 20)));
        Button guide = Button.builder(Component.literal(com.jrpetty.mcassistant.client.guide.GuideClient.label()),   // [guide31] what happens next, and everything else, asked
            b -> com.jrpetty.mcassistant.client.guide.GuideClient.openAt(this, "found_after")).bounds(left + PAD, top + h - PAD - 20, 70, 20).build(UiButton::new);
        guide.setTooltip(Tooltip.create(Component.literal(com.jrpetty.mcassistant.client.guide.GuideClient.welcomeTip())));
        this.addRenderableWidget(guide);
    }

    private void step(String label, String action, String tip, int x, int y, int w) {
        Button b = Button.builder(Component.literal(label), btn -> {
            CompoundTag a = new CompoundTag();
            a.putIntArray("heart", data.getIntArray("heart"));
            FoundingClient.ask(action, a);
        }).bounds(x, y, w, 20).build(UiButton::new);
        b.setTooltip(Tooltip.create(Component.literal(tip)));
        this.addRenderableWidget(b);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        UiStyle.shadow(g, left, top, W, h);
        UiStyle.panel(g, left, top, W, h);
        UiStyle.headerBand(g, left, top, W, 20);
        int x = left + PAD, inner = W - PAD * 2;
        UiStyle.title(g, this.font, this.title.getString(), x, top + 7);
        int folk = data.getInt("folk"), across = data.getInt("across"), dist = data.getInt("dist");
        int y = top + 26;
        UiStyle.heading(g, this.font, "What happens next", x, y, inner);
        y += 12;
        y = para(g, "1. The ground is made level round the spot, about " + across + " by " + across + " blocks: hills cut, hollows filled, trees cleared.", x, y, inner);
        y = para(g, "2. When the heart is level the first of the " + folk + " founders comes with the stores and puts the village board up; the rest follow a few at a time.", x, y, inner);
        y = para(g, "3. They choose their trades, pitch camp and start on the first buildings: the well, houses, fields. You need do nothing.", x, y, inner);
        String where = data.getBoolean("carried") ? "You are at the edge of its ground." : dist <= 16 ? "You are standing at it."
            : "It is " + dist + " blocks " + data.getString("bearing") + ": the outline on the ground shows where.";
        y = para(g, where, x, y + 2, inner);
        UiStyle.heading(g, this.font, "Next steps (once they have come)", x, top + h - PAD - 20 - 44 - 12, inner);
    }

    private int para(GuiGraphics g, String text, int x, int y, int w) {
        var lines = TextCache.split(this.font, text, w);
        for (var l : lines) {
            g.drawString(this.font, l, x, y, UiStyle.TEXT, false);
            y += 10;
        }
        return y + 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
