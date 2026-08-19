package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.net.PlotListPayload;
import com.jrpetty.mcassistant.net.PlotOrderPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The plot book, on a key: every piece of ground you have staked, and the
 * whole crew beside it. Pick a plot on the left, click hands on the right —
 * that is the entire management model. Stake new ground from here too.
 *
 * <p>The list itself is the server's answer to opening the screen, and every
 * button's reply is the refreshed book — so what you see is always what the
 * server believes, never a stale client copy.
 */
public class PlotsScreen extends Screen {

    private static final int W = 340, H = 236;
    private static final int PAD = 10;
    // 56, not 44: the hint line under the header runs to y≈38 and the section
    // headings sit at LIST_TOP-11 — at 44 they overlapped by five pixels,
    // which the layout render caught before anyone saw it in game.
    private static final int LIST_TOP = 56;
    private static final int PLOT_W = 158;
    private static final int PLOT_ROW_H = 18;
    private static final int CREW_ROW_H = 16;
    private static final int MAX_PLOTS = 7;
    private static final int MAX_CREW = 8;

    private List<PlotListPayload.Entry> plots;
    private final List<AssistantEntity> crew = new ArrayList<>();
    @Nullable private String picked;          // by name, so refreshes keep it
    private int left, top;

    public PlotsScreen(List<PlotListPayload.Entry> plots) {
        super(Component.literal("Plots"));
        this.plots = plots;
    }

    /** A fresh book from the server — keep the selection if it survived. */
    public void setPlots(List<PlotListPayload.Entry> plots) {
        this.plots = plots;
        if (picked != null && byName(picked) == null) picked = null;
        if (picked == null && !plots.isEmpty()) picked = plots.get(0).name();
    }

    @Nullable
    private PlotListPayload.Entry byName(String name) {
        for (PlotListPayload.Entry e : plots) {
            if (e.name().equals(name)) return e;
        }
        return null;
    }

    @Override
    protected void init() {
        this.left = (this.width - W) / 2;
        this.top = (this.height - H) / 2;
        if (picked == null && !plots.isEmpty()) picked = plots.get(0).name();

        crew.clear();
        if (this.minecraft != null && this.minecraft.player != null) {
            crew.addAll(this.minecraft.player.level().getEntitiesOfClass(
                AssistantEntity.class,
                this.minecraft.player.getBoundingBox().inflate(256.0),
                AssistantEntity::isAlive));
            crew.sort((a, b) -> a.clientName().compareToIgnoreCase(b.clientName()));
        }

        int gap = 4;
        int inner = W - PAD * 2;
        int w4 = (inner - gap * 3) / 4;
        int y = top + H - PAD - 18;
        int x = left + PAD;

        this.addRenderableWidget(Button.builder(Component.literal("Stake Here"),
                b -> send(1, -1, ""))
            .bounds(x, y, w4, 18)
            .tooltip(Tooltip.create(Component.literal(
                "Stake a new 25×25 plot where you stand — named for you, crewed from this menu")))
            .build());
        this.addRenderableWidget(Button.builder(Component.literal("Name ›"),
                b -> { if (picked != null) send(4, -1, picked); })
            .bounds(x + (w4 + gap), y, w4, 18)
            .tooltip(Tooltip.create(Component.literal(
                "Cycle the picked plot through the name pool — the crew's status lines follow")))
            .build());
        this.addRenderableWidget(Button.builder(Component.literal("Forget"),
                b -> { if (picked != null) send(3, -1, picked); })
            .bounds(x + 2 * (w4 + gap), y, w4, 18)
            .tooltip(Tooltip.create(Component.literal(
                "Strike the picked plot from the book — anyone working it keeps working")))
            .build());
        this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose())
            .bounds(x + 3 * (w4 + gap), y, w4, 18).build());
    }

    private static void send(int op, int entityId, String plotName) {
        PacketDistributor.sendToServer(new PlotOrderPayload(op, entityId, plotName));
    }

    @Override
    public void tick() {
        super.tick();
        crew.removeIf(a -> !a.isAlive());
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int x = left + PAD;
        // The plot list.
        int rows = Math.min(plots.size(), MAX_PLOTS);
        for (int i = 0; i < rows; i++) {
            int ry = top + LIST_TOP + i * PLOT_ROW_H;
            if (mx >= x && mx <= x + PLOT_W && my >= ry && my < ry + PLOT_ROW_H - 1) {
                picked = plots.get(i).name();
                return true;
            }
        }
        // The crew: a click puts them on the picked plot, or takes them off it.
        int cx = x + PLOT_W + 8;
        int cw = W - PAD * 2 - PLOT_W - 8;
        int crews = Math.min(crew.size(), MAX_CREW);
        for (int i = 0; i < crews; i++) {
            int ry = top + LIST_TOP + i * CREW_ROW_H;
            if (mx >= cx && mx <= cx + cw && my >= ry && my < ry + CREW_ROW_H - 1) {
                if (picked != null) send(2, crew.get(i).getId(), picked);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    /** All painting below the widgets — inside the one blur pass, per b86. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        Ui.panel(g, left, top, W, H, 26);

        int x = left + PAD;
        int inner = W - PAD * 2;
        g.drawString(this.font, "The plots", x, top + 8, Ui.INK, false);
        Ui.right(g, this.font,
            plots.size() + (plots.size() == 1 ? " plot" : " plots") + "  ·  "
            + crew.size() + (crew.size() == 1 ? " assistant" : " assistants"),
            left + W - PAD, top + 8, Ui.MUTED);
        g.drawString(this.font, "Pick a plot, then click crew to put them on it — or off it.",
            x, top + 31, Ui.FAINT, false);

        // ---- left: the ground ----
        Ui.section(g, this.font, "Ground", x, top + LIST_TOP - 11, PLOT_W);
        if (plots.isEmpty()) {
            g.drawString(this.font, "No plots yet.", x, top + LIST_TOP + 4, Ui.MUTED, false);
            g.drawString(this.font, "Stake one here, or mark", x, top + LIST_TOP + 18, Ui.FAINT, false);
            g.drawString(this.font, "ground with the wand.", x, top + LIST_TOP + 28, Ui.FAINT, false);
        }
        int rows = Math.min(plots.size(), MAX_PLOTS);
        for (int i = 0; i < rows; i++) {
            PlotListPayload.Entry e = plots.get(i);
            int ry = top + LIST_TOP + i * PLOT_ROW_H;
            boolean sel = e.name().equals(picked);
            boolean hover = mouseX >= x && mouseX <= x + PLOT_W
                && mouseY >= ry && mouseY < ry + PLOT_ROW_H - 1;
            g.fill(x, ry, x + PLOT_W, ry + PLOT_ROW_H - 1,
                sel ? Ui.ROW_PICK : (hover || i % 2 == 0 ? Ui.ROW : Ui.ROW_ALT));
            if (sel) g.renderOutline(x, ry, PLOT_W, PLOT_ROW_H - 1, Ui.ACCENT);
            Ui.chip(g, x + 3, ry + 5, jobColour(e.job()));
            g.drawString(this.font, Ui.clip(this.font, e.name(), PLOT_W - 60),
                x + 13, ry + 5, sel ? Ui.INK : Ui.MUTED, false);
            String load = e.workers() > 0 ? e.workers() + "⚒" : "—";
            Ui.right(g, this.font, e.sizeX() + "×" + e.sizeZ() + " " + load,
                x + PLOT_W - 3, ry + 5, e.workers() > 0 ? Ui.ACCENT : Ui.FAINT);
        }
        if (plots.size() > MAX_PLOTS) {
            g.drawString(this.font, "+" + (plots.size() - MAX_PLOTS) + " more",
                x, top + LIST_TOP + rows * PLOT_ROW_H + 2, Ui.FAINT, false);
        }

        // ---- right: the hands ----
        int cx = x + PLOT_W + 8;
        int cw = inner - PLOT_W - 8;
        Ui.section(g, this.font, "Crew", cx, top + LIST_TOP - 11, cw);
        if (crew.isEmpty()) {
            g.drawString(this.font, "Nobody nearby.", cx, top + LIST_TOP + 4, Ui.MUTED, false);
        }
        int crews = Math.min(crew.size(), MAX_CREW);
        for (int i = 0; i < crews; i++) {
            AssistantEntity a = crew.get(i);
            BotInfo info = BotInfo.of(a);
            int ry = top + LIST_TOP + i * CREW_ROW_H;
            boolean onPicked = picked != null && picked.equals(info.patchName());
            boolean hover = mouseX >= cx && mouseX <= cx + cw
                && mouseY >= ry && mouseY < ry + CREW_ROW_H - 1;
            g.fill(cx, ry, cx + cw, ry + CREW_ROW_H - 1,
                onPicked ? Ui.ROW_PICK : (hover || i % 2 == 0 ? Ui.ROW : Ui.ROW_ALT));
            Ui.chip(g, cx + 3, ry + 4, Ui.job(a.clientJobOrdinal()));
            int tx = cx + 13;
            int lvl = a.clientLevel();
            if (lvl >= 1) {
                String star = "✦" + lvl;
                g.drawString(this.font, star, tx, ry + 4, Ui.ACCENT, false);
                tx += this.font.width(star) + 4;
            }
            g.drawString(this.font, Ui.clip(this.font, a.clientName(), cw - 78),
                tx, ry + 4, onPicked ? Ui.INK : Ui.MUTED, false);
            String where = onPicked ? "on it"
                : info.patchName().isEmpty() ? "free" : info.patchName();
            Ui.right(g, this.font, Ui.clip(this.font, where, 64), cx + cw - 3, ry + 4,
                onPicked ? Ui.GOOD : Ui.FAINT);
        }
        if (crew.size() > MAX_CREW) {
            g.drawString(this.font, "+" + (crew.size() - MAX_CREW) + " more nearby",
                cx, top + LIST_TOP + crews * CREW_ROW_H + 2, Ui.FAINT, false);
        }

        // A hairline between the columns, engraved like the section rules.
        int lx = x + PLOT_W + 3;
        g.fill(lx, top + LIST_TOP - 6, lx + 1, top + H - PAD - 24, Ui.EDGE_SOFT);
        g.fill(lx + 1, top + LIST_TOP - 6, lx + 2, top + H - PAD - 24, Ui.HI);
    }

    /** The trade's colour by its display title, slate for bare ground. */
    private static int jobColour(String title) {
        if (!title.isEmpty()) {
            for (AssistantEntity.StationTask t : AssistantEntity.StationTask.values()) {
                if (title.equals(t.title)) return Ui.job(t.ordinal());
            }
        }
        return Ui.job(0);   // ordinal 0: unassigned slate
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
