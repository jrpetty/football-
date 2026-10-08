package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.net.CivicPagePayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * [player-civic] A civic page (CivicPagePayload): the Leader's page, the hustings, the trades. The server's words, a
 * line to each part with its label in bold, and under them its buttons, each running a command as the player (the
 * command answers with the page brought up to date).
 */
public class CivicScreen extends Screen {

    private static final int W = 344, PAD = 10, ROW = 20, COLS = 4;

    private final CivicPagePayload page;
    private final List<FormattedCharSequence> lines = new ArrayList<>();
    private int left, top, scroll, high = 240;

    public CivicScreen(CivicPagePayload page) {
        super(Component.literal(page.title()));
        this.page = page;
    }

    /** The server's page: shown, or brought up to date in place if it is already open; an empty one shuts it (/village civic close). */
    public static void show(CivicPagePayload page) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (page.title().isEmpty() && page.text().isEmpty()) {
            if (mc.screen instanceof CivicScreen open) open.onClose();
            return;
        }
        mc.setScreen(new CivicScreen(page));
    }

    private int rows() {
        return (page.buttons().size() + COLS - 1) / COLS;
    }

    @Override
    protected void init() {
        this.high = Math.max(200, Math.min(300, this.height - 12));
        this.left = (this.width - W) / 2;
        this.top = (this.height - high) / 2;
        lines.clear();
        for (String part : page.text().split("\n", -1)) {
            String p = part.trim();
            if (p.isEmpty()) { lines.add(FormattedCharSequence.EMPTY); continue; }
            int colon = p.indexOf(": ");
            Component c = colon > 0 && colon < 34 && !p.startsWith("-")
                ? Component.empty().append(Component.literal(p.substring(0, colon + 1)).withStyle(ChatFormatting.BOLD))
                    .append(Component.literal(p.substring(colon + 1)))
                : Component.literal(p);
            lines.addAll(this.font.split(c, W - PAD * 2 - 6));
        }
        int bw = (W - PAD * 2 - (COLS - 1) * 3) / COLS;
        int y0 = top + high - PAD - 18 - 4 - rows() * ROW;
        for (int i = 0; i < page.buttons().size(); i++) {
            String[] b = page.buttons().get(i).split("\t", 3);
            if (b.length < 2) continue;
            String command = b[1];
            int x = left + PAD + (i % COLS) * (bw + 3), y = y0 + (i / COLS) * ROW;
            Button btn = Button.builder(Component.literal(Ui.clip(this.font, b[0], bw - 6)), x2 -> run(command)).bounds(x, y, bw, ROW - 2).build();
            btn.setTooltip(Tooltip.create(Component.literal(b.length > 2 ? b[0] + " — " + b[2] : b[0])));
            this.addRenderableWidget(btn);
        }
        this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose())
            .bounds(left + W - PAD - 50, top + high - PAD - 18, 50, 18).build());
    }

    private void run(String command) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.player.connection.sendCommand(command.startsWith("/") ? command.substring(1) : command);
    }

    private int visible() {
        return (high - 26 - PAD - 18 - 8 - rows() * ROW) / (this.font.lineHeight + 1);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        scroll = Math.max(0, Math.min(Math.max(0, lines.size() - visible()), scroll - (int) Math.signum(dy) * 3));
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        Ui.panel(g, left, top, W, high, 22);
        g.drawString(this.font, Ui.clip(this.font, this.title.getString(), W - PAD * 2), left + PAD, top + 7, Ui.INK, false);
        int y = top + 26;
        int shown = visible();
        for (int i = scroll; i < Math.min(lines.size(), scroll + shown); i++) {
            g.drawString(this.font, lines.get(i), left + PAD, y, Ui.INK, false);
            y += this.font.lineHeight + 1;
        }
        if (lines.size() > shown) {
            g.drawString(this.font, (scroll + 1) + "–" + Math.min(lines.size(), scroll + shown) + " of " + lines.size() + " (scroll)",
                left + PAD, top + high - PAD - 13, Ui.MUTED, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
