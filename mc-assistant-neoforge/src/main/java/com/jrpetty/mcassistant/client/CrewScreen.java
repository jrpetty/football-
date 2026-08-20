package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.AssistantActions;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The crew list: every assistant, by level, name, job and duty, with a live
 * status light down the side. Pick one and the buttons below order it about.
 *
 * Deliberately NOT an inventory screen — this tells specialists what to do; a
 * bot's pack stays behind right-clicking the bot itself.
 */
public class CrewScreen extends Screen {

    private static final int W = 300, H = 236;
    private static final int PAD = 10;
    private static final int ROW_H = 16;
    private static final int MAX_ROWS = 4;

    private final List<AssistantEntity> crew = new ArrayList<>();
    private int picked = -1;
    private int left, top, listTop;

    /** How the list reads: 0 by name, 1 by job, 2 problems first. Static so
     *  the choice survives closing the screen — a sort you have to re-pick
     *  every time is a sort nobody uses. */
    private static int sortMode;
    private static final String[] SORT_LABELS = { "A–Z", "Job", "Stuck" };

    private void sortCrew() {
        switch (sortMode) {
            case 1 -> crew.sort(java.util.Comparator
                .comparingInt(AssistantEntity::clientJobOrdinal)
                .thenComparing(a -> a.clientName(), String.CASE_INSENSITIVE_ORDER));
            case 2 -> crew.sort(java.util.Comparator
                .comparingInt((AssistantEntity a) -> {
                    int c = Ui.statusColour(a.clientStatus());
                    return c == Ui.BAD ? 0 : c == Ui.WARN ? 1 : 2;   // worst first
                })
                .thenComparing(a -> a.clientName(), String.CASE_INSENSITIVE_ORDER));
            default -> crew.sort((a, b) -> a.clientName().compareToIgnoreCase(b.clientName()));
        }
    }

    public CrewScreen() {
        super(Component.literal("Crew"));
    }

    @Override
    protected void init() {
        this.left = (this.width - W) / 2;
        this.top = (this.height - H) / 2;
        this.listTop = top + 42;

        crew.clear();
        if (this.minecraft != null && this.minecraft.player != null) {
            crew.addAll(this.minecraft.player.level().getEntitiesOfClass(
                AssistantEntity.class,
                this.minecraft.player.getBoundingBox().inflate(256.0),
                AssistantEntity::isAlive));
            sortCrew();
        }
        if (picked >= crew.size()) picked = crew.isEmpty() ? -1 : 0;
        if (picked < 0 && !crew.isEmpty()) picked = 0;

        // The sort toggle sits by the headline, above the list it reorders.
        this.addRenderableWidget(Button.builder(Component.literal(SORT_LABELS[sortMode]), b -> {
                AssistantEntity keep = picked >= 0 && picked < crew.size() ? crew.get(picked) : null;
                sortMode = (sortMode + 1) % SORT_LABELS.length;
                sortCrew();
                picked = keep == null ? picked : Math.max(0, crew.indexOf(keep));
                b.setMessage(Component.literal(SORT_LABELS[sortMode]));
            })
            .bounds(left + W - PAD - 44, top + 24, 44, 14)
            .tooltip(Tooltip.create(Component.literal(
                "Sort the crew: by name, by trade, or problems first")))
            .build());

        // Rows are drawn by hand and clicked through mouseClicked — a stack of
        // vanilla buttons looked like a toolbar, not a list.
        int x = left + PAD;
        int inner = W - PAD * 2;
        int h = 18, gap = 4;
        int w4 = (inner - gap * 3) / 4;
        int by = top + H - PAD - h * 3 - gap * 2;

        order(x, by, w4, h, "Work", AssistantActions.WORK, "Start or pause its job");
        order(x + (w4 + gap), by, w4, h, "Job ›", AssistantActions.JOB_NEXT, "Change what it specialises in");
        order(x + 2 * (w4 + gap), by, w4, h, "Duty", AssistantActions.CYCLE_SHIFT, "Days / nights / both");
        order(x + 3 * (w4 + gap), by, w4, h, "Stash", AssistantActions.STASH, "Empty its pack into a chest");

        by += h + gap;
        order(x, by, w4, h, "Come", AssistantActions.COME, "Walk to me now");
        order(x + (w4 + gap), by, w4, h, "Stay", AssistantActions.STAY, "Wait there (stops work)");
        order(x + 2 * (w4 + gap), by, w4, h, "Bed", AssistantActions.CLAIM_BED, "Give it the nearest bed to you");
        this.addRenderableWidget(Button.builder(Component.literal("Orders"), b -> {
                if (picked >= 0 && picked < crew.size() && this.minecraft != null) {
                    this.minecraft.setScreen(new OrdersScreen(crew.get(picked)));
                }
            })
            .bounds(x + 3 * (w4 + gap), by, w4, h)
            .tooltip(Tooltip.create(Component.literal("Everything you can tell this one to do")))
            .build());

        // Patches you've set up once and can crew up later.
        by += h + gap;
        order(x, by, w4, h, "Save Patch", AssistantActions.SAVE_PRESET,
            "Remember this one's job and ground as a preset");
        order(x + (w4 + gap), by, w4, h, "Use Patch", AssistantActions.USE_PRESET,
            "Put this one on a saved patch — press again for the next one");
        this.addRenderableWidget(Button.builder(Component.literal("Plots"),
                b -> { this.onClose(); PlotsClient.requestOpen(); })
            .bounds(x + 2 * (w4 + gap), by, w4, h)
            .tooltip(Tooltip.create(Component.literal(
                "The plot book: every piece of ground, its yield and condition, "
                + "and who's working it — crew a plot with a click")))
            .build());
        this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose())
            .bounds(x + 3 * (w4 + gap), by, w4, h).build());
    }

    private void order(int x, int y, int w, int h, String label, int action, String tip) {
        this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
            if (picked >= 0 && picked < crew.size()) OrdersScreen.sendOrder(crew.get(picked), action);
        }).bounds(x, y, w, h).tooltip(Tooltip.create(Component.literal(tip))).build());
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int rows = Math.min(crew.size(), MAX_ROWS);
        if (mx >= left + PAD && mx <= left + W - PAD) {
            for (int i = 0; i < rows; i++) {
                int ry = listTop + i * ROW_H;
                if (my >= ry && my < ry + ROW_H) {
                    picked = i;
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
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
        Ui.panel(g, left, top, W, H, 26);

        int x = left + PAD;
        int inner = W - PAD * 2;

        g.drawString(this.font, "Your crew", x, top + 8, Ui.INK, false);
        // The bill, not the headcount: a crew you cannot pay is a crew that
        // stops working, and that is worth seeing before it happens.
        int iron = 0, gold = 0, diamond = 0, owed = 0;
        for (AssistantEntity a : crew) {
            BotInfo bi = BotInfo.of(a);
            iron += bi.ironPaid();
            gold += bi.goldPaid();
            diamond += bi.diamondPaid();
            if (bi.wageDueTicks() <= 0) owed++;
        }
        Ui.right(g, this.font,
            crew.size() + (crew.size() == 1 ? " assistant" : " assistants")
            + (owed > 0 ? "  ·  " + owed + " unpaid" : ""),
            left + W - PAD, top + 8, owed > 0 ? Ui.BAD : Ui.MUTED);
        // What the crew is short of, in its own words. A shortage someone else
        // can carry over is worth seeing before it stops anybody working.
        java.util.List<String> asking = new java.util.ArrayList<>();
        for (AssistantEntity a : crew) {
            String st = a.clientStatus();
            if (st.startsWith("Needs")) asking.add(a.clientName() + ": " + st.substring(6));
        }
        // Clipped short of the sort toggle that now lives on this line's right.
        int lineW = inner - 50;
        if (asking.isEmpty()) {
            g.drawString(this.font, Ui.clip(this.font,
                "wages so far: " + iron + " iron  ·  " + gold + " gold  ·  " + diamond + " diamond",
                lineW), x, top + 30, Ui.FAINT, false);
        } else {
            g.drawString(this.font, Ui.clip(this.font,
                "asking for — " + String.join("  ·  ", asking), lineW),
                x, top + 30, Ui.WARN, false);
        }

        if (crew.isEmpty()) {
            g.drawString(this.font, "Nobody here yet.", x, listTop + 6, Ui.MUTED, false);
            g.drawString(this.font, "Place an Assistant Spawner to hire one.",
                x, listTop + 18, Ui.FAINT, false);
            return;
        }

        // The list: banded rows, a status light, level, name, job and duty.
        int rows = Math.min(crew.size(), MAX_ROWS);
        for (int i = 0; i < rows; i++) {
            AssistantEntity a = crew.get(i);
            int ry = listTop + i * ROW_H;
            boolean sel = i == picked;
            boolean hover = mouseX >= x && mouseX <= left + W - PAD && mouseY >= ry && mouseY < ry + ROW_H;
            g.fill(x, ry, left + W - PAD, ry + ROW_H - 1,
                sel ? Ui.ROW_PICK : (hover ? Ui.ROW_ALT : (i % 2 == 0 ? Ui.ROW : Ui.ROW_ALT)));

            int light = Ui.statusColour(a.clientStatus());
            g.fill(x, ry, x + 2, ry + ROW_H - 1, light);           // status spine
            if (sel) g.renderOutline(x, ry, inner, ROW_H - 1, Ui.ACCENT);

            // The trade's colour, same as its uniform and its map chip — you
            // can find the farmer in the list the way you find it in a field.
            Ui.chip(g, x + 5, ry + 4, Ui.job(a.clientJobOrdinal()));
            int tx = x + 15;
            int lvl = a.clientLevel();
            if (lvl >= 1) {
                String star = "✦" + lvl;
                g.drawString(this.font, star, tx, ry + 4, Ui.ACCENT, false);
                tx += this.font.width(star) + 5;
            }
            g.drawString(this.font, a.clientName(), tx, ry + 4, sel ? Ui.INK : Ui.MUTED, false);

            AssistantEntity.StationTask trade =
                AssistantEntity.StationTask.byOrdinal(a.clientJobOrdinal());
            String rank = Ladder.rank(trade, a.clientLevel());
            int nameEnd = tx + this.font.width(a.clientName()) + 8;
            Ui.right(g, this.font, Ui.clip(this.font,
                rank.isEmpty() ? trade.title + "  ·  " + a.clientShift().label
                    : trade.title + "  ·  " + rank,
                left + W - PAD - 6 - nameEnd),
                left + W - PAD - 6, ry + 4, sel ? Ui.MUTED : Ui.FAINT);
        }
        if (crew.size() > MAX_ROWS) {
            g.drawString(this.font, "+" + (crew.size() - MAX_ROWS) + " more nearby",
                x, listTop + rows * ROW_H + 2, Ui.FAINT, false);
        }

        // Detail for the selected one, just above the buttons.
        if (picked >= 0 && picked < crew.size()) {
            AssistantEntity a = crew.get(picked);
            int dy = top + H - PAD - 18 * 3 - 4 * 2 - 30;
            Ui.section(g, this.font, a.clientName(), x, dy - 12, inner);
            String status = a.clientStatus();
            g.drawString(this.font, Ui.clip(this.font, status, inner), x, dy, Ui.statusColour(status), false);
            g.drawString(this.font, Ui.clip(this.font, a.clientZone(), inner), x, dy + 10, Ui.FAINT, false);
            // The ladder, in one line: rank, level, and what the next rung
            // opens — the record sheet has the full climb.
            AssistantEntity.StationTask pt =
                AssistantEntity.StationTask.byOrdinal(a.clientJobOrdinal());
            Ladder.Rung next = null;
            for (Ladder.Rung r : Ladder.forTrade(pt)) {
                if (a.clientLevel() < r.level()) { next = r; break; }
            }
            String rk = Ladder.rank(pt, a.clientLevel());
            String climb = next == null
                ? (rk.isEmpty() ? "" : rk + " — the top of the ladder")
                : (rk.isEmpty() ? "" : rk + " · ") + "lv " + a.clientLevel()
                    + " — next lv" + next.level() + ": "
                    + (next.title().isEmpty() ? "" : next.title() + ", ") + next.gives();
            if (!climb.isEmpty()) {
                g.drawString(this.font, Ui.clip(this.font, climb, inner), x, dy + 20, Ui.ACCENT, false);
            }
        }

    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
