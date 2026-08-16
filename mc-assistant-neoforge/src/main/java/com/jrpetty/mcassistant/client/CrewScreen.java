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
            crew.sort((a, b) -> a.clientName().compareToIgnoreCase(b.clientName()));
        }
        if (picked >= crew.size()) picked = crew.isEmpty() ? -1 : 0;
        if (picked < 0 && !crew.isEmpty()) picked = 0;

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
        this.addRenderableWidget(Button.builder(Component.literal("Map"),
                b -> this.minecraft.setScreen(new MapScreen()))
            .bounds(x + 2 * (w4 + gap), by, w4, h)
            .tooltip(Tooltip.create(Component.literal(
                "Every patch and every specialist, seen from above")))
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

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g, mouseX, mouseY, partialTick);
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
        g.drawString(this.font, Ui.clip(this.font,
            "wages so far: " + iron + " iron  ·  " + gold + " gold  ·  " + diamond + " diamond",
            inner), x, top + 30, Ui.FAINT, false);

        if (crew.isEmpty()) {
            g.drawString(this.font, "Nobody here yet.", x, listTop + 6, Ui.MUTED, false);
            g.drawString(this.font, "Place an Assistant Spawner to hire one.",
                x, listTop + 18, Ui.FAINT, false);
            super.render(g, mouseX, mouseY, partialTick);
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

            int tx = x + 8;
            int lvl = a.clientLevel();
            if (lvl >= 1) {
                String star = "✦" + lvl;
                g.drawString(this.font, star, tx, ry + 4, Ui.ACCENT, false);
                tx += this.font.width(star) + 5;
            }
            g.drawString(this.font, a.clientName(), tx, ry + 4, sel ? Ui.INK : Ui.MUTED, false);

            String job = AssistantEntity.StationTask.byOrdinal(a.clientJobOrdinal()).title;
            String duty = a.clientShift().label;
            Ui.right(g, this.font, job + "  ·  " + duty, left + W - PAD - 6, ry + 4,
                sel ? Ui.MUTED : Ui.FAINT);
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
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
