package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.AssistantActions;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The crew list: every assistant you have, by name and job, with its live
 * status. Pick one and you get its orders — work, stop, follow, stay, come,
 * stash, go home, set its area.
 *
 * Deliberately NOT an inventory screen: this is for telling a specialist what
 * to do, not for rummaging in its pack. Its pack lives behind right-clicking
 * the bot itself.
 */
public class CrewScreen extends Screen {

    private static final int W = 300, H = 216;
    private static final int PANEL      = 0xE8181A14;
    private static final int PANEL_EDGE = 0xFF3A4029;
    private static final int HEADER     = 0xFF232A1B;
    private static final int ROW        = 0xFF1F2318;
    private static final int ROW_PICKED = 0xFF2C3A24;
    private static final int INK        = 0xFFEAEDE0;
    private static final int MUTED      = 0xFF9AA087;
    private static final int GOOD       = 0xFF78C78C;
    private static final int WARN       = 0xFFE0A83A;
    private static final int BAD        = 0xFFDE6C51;

    private final List<AssistantEntity> crew = new ArrayList<>();
    private int picked = -1;
    private int left, top;

    public CrewScreen() {
        super(Component.literal("Crew"));
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
            crew.sort((a, b) -> a.clientName().compareToIgnoreCase(b.clientName()));
        }
        if (picked >= crew.size()) picked = crew.isEmpty() ? -1 : 0;
        if (picked < 0 && !crew.isEmpty()) picked = 0;

        // One clickable row per assistant.
        int rowY = top + 32;
        for (int i = 0; i < crew.size() && i < 6; i++) {
            final int index = i;
            this.addRenderableWidget(Button.builder(Component.literal(rowLabel(crew.get(i))),
                b -> { picked = index; rebuild(); })
                .bounds(left + 10, rowY, W - 20, 18).build());
            rowY += 20;
        }

        // Orders for whoever is selected. No pack button: this screen gives
        // instructions, it does not manage anybody's inventory.
        int by = top + H - 66, bw = (W - 20 - 12) / 4, bh = 18, gap = 4;
        order(left + 10, by, bw, bh, "Work/Pause", AssistantActions.WORK);
        order(left + 10 + (bw + gap), by, bw, bh, "Job >", AssistantActions.JOB_NEXT);
        order(left + 10 + 2 * (bw + gap), by, bw, bh, "Stash", AssistantActions.STASH);
        order(left + 10 + 3 * (bw + gap), by, bw, bh, "Report", AssistantActions.REPORT);
        by += bh + gap;
        order(left + 10, by, bw, bh, "Follow", AssistantActions.FOLLOW);
        order(left + 10 + (bw + gap), by, bw, bh, "Stay", AssistantActions.STAY);
        order(left + 10 + 2 * (bw + gap), by, bw, bh, "Come", AssistantActions.COME);
        order(left + 10 + 3 * (bw + gap), by, bw, bh, "Go Home", AssistantActions.GO_HOME);
        by += bh + gap;
        order(left + 10, by, bw, bh, "Set Area", AssistantActions.ZONE_HERE);
        order(left + 10 + (bw + gap), by, bw, bh, "Show Area", AssistantActions.ZONE_SHOW);
        order(left + 10 + 2 * (bw + gap), by, bw, bh, "Stop", AssistantActions.STOP);
        this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose())
            .bounds(left + 10 + 3 * (bw + gap), by, bw, bh).build());
    }

    private void rebuild() {
        this.clearWidgets();
        this.init();
    }

    private void order(int x, int y, int w, int h, String label, int action) {
        this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
            if (picked >= 0 && picked < crew.size()) {
                OrdersScreen.sendOrder(crew.get(picked), action);
            }
        }).bounds(x, y, w, h).build());
    }

    private String rowLabel(AssistantEntity a) {
        String job = AssistantEntity.StationTask.byOrdinal(a.clientJobOrdinal()).title;
        return a.clientName() + "  —  " + job;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(left, top, left + W, top + H, PANEL);
        g.fill(left, top, left + W, top + 26, HEADER);
        g.renderOutline(left, top, W, H, PANEL_EDGE);

        g.drawString(this.font, "Your crew (" + crew.size() + ")", left + 10, top + 9, INK, false);
        g.drawString(this.font, "; to open", left + W - 10 - this.font.width("; to open"),
            top + 9, MUTED, false);

        if (crew.isEmpty()) {
            g.drawString(this.font, "No assistants nearby.", left + 10, top + 40, MUTED, false);
            g.drawString(this.font, "Place an Assistant Spawner to hire one.",
                left + 10, top + 52, MUTED, false);
        }

        super.render(g, mouseX, mouseY, partialTick);

        // Highlight the selected one and show its live status underneath.
        if (picked >= 0 && picked < crew.size()) {
            AssistantEntity a = crew.get(picked);
            int y = top + H - 88;
            String status = a.clientStatus();
            int colour = status.startsWith("Working") ? GOOD
                : status.startsWith("Needs") || status.startsWith("Out of") ? BAD : WARN;
            g.drawString(this.font, a.clientName() + ": " + status, left + 10, y, colour, false);
            g.drawString(this.font, a.clientZone(), left + 10, y + 11, MUTED, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
