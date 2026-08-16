package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * One specialist's career: how long it has served, what it chose to specialise
 * in, and everything it has done — blocks mined, animals bred, fish caught —
 * counted for its whole life and across every revival.
 */
public class RecordScreen extends Screen {

    private static final int W = 260, H = 216;
    private static final int PAD = 10;

    private final AssistantEntity bot;
    private int left, top;

    public RecordScreen(AssistantEntity bot) {
        super(Component.literal("Work record"));
        this.bot = bot;
    }

    @Override
    protected void init() {
        this.left = (this.width - W) / 2;
        this.top = (this.height - H) / 2;
        int gap = 4;
        int w3 = (W - PAD * 2 - gap * 2) / 3;
        int y = top + H - PAD - 18;
        this.addRenderableWidget(Button.builder(Component.literal("Specialise"), b ->
                OrdersScreen.sendOrder(bot, com.jrpetty.mcassistant.AssistantActions.CYCLE_BRANCH))
            .bounds(left + PAD, y, w3, 18)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                "At level 20 a specialist can pick a branch to deepen its trade")))
            .build());
        this.addRenderableWidget(Button.builder(Component.literal("Orders"),
                b -> this.minecraft.setScreen(new OrdersScreen(bot)))
            .bounds(left + PAD + w3 + gap, y, w3, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose())
            .bounds(left + PAD + 2 * (w3 + gap), y, w3, 18).build());
    }

    @Override
    public void tick() {
        super.tick();
        if (!bot.isAlive()) this.onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g, mouseX, mouseY, partialTick);
        Ui.panel(g, left, top, W, H, 30);

        int x = left + PAD;
        int inner = W - PAD * 2;
        BotInfo info = BotInfo.of(bot);
        int lvl = bot.clientLevel();

        // Who, and how far along.
        String name = bot.clientName();
        g.drawString(this.font, name, x, top + 7, Ui.INK, false);
        if (lvl >= 1) {
            g.drawString(this.font, "✦" + lvl, x + this.font.width(name) + 6, top + 7, Ui.ACCENT, false);
        }
        Ui.right(g, this.font,
            AssistantEntity.StationTask.byOrdinal(bot.clientJobOrdinal()).title,
            left + W - PAD, top + 7, Ui.MUTED);
        g.drawString(this.font,
            info.daysServed() + " days served"
            + (info.hasBranch() ? "  ·  " + info.branch() : "")
            + "  ·  works " + bot.clientShift().label,
            x, top + 18, Ui.FAINT, false);

        // What it has cost you. Wages are spent, not carried — this is the only
        // place the metal you handed over is ever accounted for.
        String wages = info.hasWages()
            ? info.ironPaid() + " iron, " + info.goldPaid() + " gold, "
              + info.diamondPaid() + " diamond  ·  " + info.wageStatus()
            : "no wages drawn yet  ·  " + info.wageStatus();
        g.drawString(this.font, Ui.clip(this.font, wages, inner), x, top + 28,
            info.wageDueTicks() <= 0 ? Ui.BAD : Ui.MUTED, false);

        // Who it is, as opposed to what it does: the quirk it arrived with, and
        // how long it has worked alongside the rest of the crew.
        if (!info.trait().isEmpty()) {
            g.drawString(this.font, Ui.clip(this.font,
                info.trait() + " — " + info.traitBlurb()
                + (info.teamwork() > 0 ? "  ·  +" + info.teamwork() + "% crew rhythm" : ""),
                inner), x, top + 38, Ui.GOOD, false);
        }

        // The career itself, biggest tally first, each with a bar for scale.
        Ui.section(g, this.font, "Career", x, top + 54, inner);
        int y = top + 66;
        int rowH = 12;
        int busiest = Math.max(1, info.busiest());

        if (!info.hasRecord()) {
            g.drawString(this.font, "Nothing on the books yet — give it a job.",
                x, y + 2, Ui.MUTED, false);
        } else {
            java.util.List<AssistantEntity.Deed> done = new java.util.ArrayList<>();
            for (AssistantEntity.Deed d : AssistantEntity.Deed.values()) {
                if (info.deed(d) > 0) done.add(d);
            }
            done.sort((a, b) -> Integer.compare(info.deed(b), info.deed(a)));

            int maxRows = (top + H - PAD - 24 - y) / rowH;
            for (int i = 0; i < done.size() && i < maxRows; i++) {
                AssistantEntity.Deed d = done.get(i);
                int count = info.deed(d);
                if (i % 2 == 0) g.fill(x - 2, y - 1, x + inner + 2, y + rowH - 2, Ui.ROW);
                // The bar sits behind the label, so the biggest jobs of a life
                // stand out without having to compare numbers.
                int barW = Math.max(1, (inner - 4) * count / busiest);
                g.fill(x - 2, y + rowH - 4, x - 2 + barW, y + rowH - 3, Ui.EDGE);
                g.drawString(this.font, d.label, x, y, Ui.INK, false);
                Ui.right(g, this.font, String.valueOf(count), x + inner, y, Ui.ACCENT);
                y += rowH;
            }
            if (done.size() > maxRows) {
                g.drawString(this.font, "+" + (done.size() - maxRows) + " more",
                    x, y, Ui.FAINT, false);
            }
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
