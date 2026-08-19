package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole operation's books: everything every assistant has ever produced,
 * summed, with a per-day rate — the difference between "the crew is busy" and
 * "the crew is producing". One page, biggest number first.
 */
public class TotalsScreen extends Screen {

    private static final int W = 280, H = 226;
    private static final int PAD = 10;

    private final List<AssistantEntity> crew = new ArrayList<>();
    private int left, top;

    public TotalsScreen() {
        super(Component.literal("Operation totals"));
    }

    @Override
    protected void init() {
        this.left = (this.width - W) / 2;
        this.top = (this.height - H) / 2;

        crew.clear();
        if (this.minecraft != null && this.minecraft.player != null) {
            crew.addAll(this.minecraft.player.level().getEntitiesOfClass(
                AssistantEntity.class,
                this.minecraft.player.getBoundingBox().inflate(256.0),
                AssistantEntity::isAlive));
        }

        int gap = 4;
        int w3 = (W - PAD * 2 - gap * 2) / 3;
        int y = top + H - PAD - 18;
        this.addRenderableWidget(Button.builder(Component.literal("Crew"),
                b -> this.minecraft.setScreen(new CrewScreen()))
            .bounds(left + PAD, y, w3, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("Map"),
                b -> this.minecraft.setScreen(new MapScreen()))
            .bounds(left + PAD + w3 + gap, y, w3, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose())
            .bounds(left + PAD + 2 * (w3 + gap), y, w3, 18).build());
    }

    @Override
    public void tick() {
        super.tick();
        crew.removeIf(a -> !a.isAlive());
    }

    /**
     * ALL of this screen's own painting happens here, layered between the
     * background and the widgets, because since 1.21 the vanilla render loop
     * runs the menu BLUR SHADER inside renderBackground — and super.render()
     * calls renderBackground itself. The old pattern (paint the panel, then
     * call super.render) ran the blur a SECOND time over everything already
     * drawn: every panel and every line of text got gaussian-smeared, and the
     * vanilla buttons were drawn crisp on top afterwards. That is the blur
     * that survived four palette overhauls — it was never contrast.
     */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        Ui.panel(g, left, top, W, H, 30);

        int x = left + PAD;
        int inner = W - PAD * 2;

        // Sum every deed across the crew; the operation's age is its oldest hand.
        int[] totals = new int[AssistantEntity.Deed.values().length];
        int days = 1;
        for (AssistantEntity a : crew) {
            BotInfo info = BotInfo.of(a);
            days = Math.max(days, Math.max(1, info.daysServed()));
            for (AssistantEntity.Deed d : AssistantEntity.Deed.values()) {
                totals[d.ordinal()] += info.deed(d);
            }
        }

        g.drawString(this.font, "The operation", x, top + 8, Ui.INK, false);
        Ui.right(g, this.font,
            crew.size() + (crew.size() == 1 ? " assistant" : " assistants")
            + "  ·  day " + days,
            left + W - PAD, top + 8, Ui.MUTED);
        g.drawString(this.font, "Everything the crew has ever produced, and the daily rate.",
            x, top + 20, Ui.FAINT, false);

        Ui.section(g, this.font, "All-time production", x, top + 36, inner);

        List<AssistantEntity.Deed> done = new ArrayList<>();
        for (AssistantEntity.Deed d : AssistantEntity.Deed.values()) {
            if (totals[d.ordinal()] > 0) done.add(d);
        }
        done.sort((a, b) -> Integer.compare(totals[b.ordinal()], totals[a.ordinal()]));

        int y = top + 48;
        int rowH = 12;
        if (done.isEmpty()) {
            g.drawString(this.font, crew.isEmpty()
                    ? "Nobody on the books — hire someone first."
                    : "Nothing produced yet — give them jobs and ground.",
                x, y + 2, Ui.MUTED, false);
        } else {
            int busiest = Math.max(1, totals[done.get(0).ordinal()]);
            int maxRows = (top + H - PAD - 24 - y) / rowH;
            for (int i = 0; i < done.size() && i < maxRows; i++) {
                AssistantEntity.Deed d = done.get(i);
                int count = totals[d.ordinal()];
                if (i % 2 == 0) g.fill(x - 2, y - 1, x + inner + 2, y + rowH - 2, Ui.ROW);
                int barW = Math.max(1, (inner - 4) * count / busiest);
                g.fill(x - 2, y + rowH - 4, x + inner + 2, y + rowH - 3, Ui.EDGE_SOFT);
                g.fill(x - 2, y + rowH - 4, x - 2 + barW, y + rowH - 3, Ui.ACCENT);
                g.drawString(this.font, d.label, x, y, Ui.INK, false);
                // The rate makes the number mean something: 4,000 wheat is a
                // career; 400 a day is a farm that feeds a server.
                String rate = count / days > 0 ? count / days + "/day" : "";
                Ui.right(g, this.font, count + (rate.isEmpty() ? "" : "  ·  " + rate),
                    x + inner, y, Ui.ACCENT);
                y += rowH;
            }
            if (done.size() > maxRows) {
                g.drawString(this.font, "+" + (done.size() - maxRows) + " more",
                    x, y, Ui.FAINT, false);
            }
        }

    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
