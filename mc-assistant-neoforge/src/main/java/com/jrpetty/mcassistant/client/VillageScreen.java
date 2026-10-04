package com.jrpetty.mcassistant.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * The village journal (J): everything /village status says about the village you stand in,
 * on a page instead of one long chat line — a line to each part (the trades, the stores, the
 * beds, the watch, the elder's orders, what it is short of), the part's name in bold.
 */
public class VillageScreen extends Screen {

    private static final int W = 320, H = 230, PAD = 10;

    private final String text;
    private final List<FormattedCharSequence> lines = new ArrayList<>();
    private int left, top, scroll;

    public VillageScreen(String title, String text) {
        super(Component.literal(title));
        this.text = text;
    }

    /** The server's answer to the journal key. */
    public static void show(String title, String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.setScreen(new VillageScreen(title, text));
    }

    /** Ask the server for the page (the J key). */
    public static void request() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.jrpetty.mcassistant.net.VillageAskPayload(0));
    }

    @Override
    protected void init() {
        this.left = (this.width - W) / 2;
        this.top = (this.height - H) / 2;
        lines.clear();
        // "Label: value. Label: value." — a paragraph to each part, its label in bold.
        for (String part : text.split("\\. (?=[A-Z][A-Za-z' ]{1,24}: )|\\. (?=Village at )")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int colon = p.indexOf(": ");
            Component c = colon > 0 && colon < 26
                ? Component.empty()
                    .append(Component.literal(p.substring(0, colon + 1)).withStyle(net.minecraft.ChatFormatting.BOLD))
                    .append(Component.literal(p.substring(colon + 1)))
                : Component.literal(p);
            lines.addAll(this.font.split(c, W - PAD * 2 - 6));
            lines.add(FormattedCharSequence.EMPTY);
        }
        this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose())
            .bounds(left + W - PAD - 60, top + H - PAD - 18, 60, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> request())
            .bounds(left + W - PAD - 126, top + H - PAD - 18, 60, 18).build());
        // Fast time, to watch the village grow: the same as the [ ] \ keys.
        int[] speeds = {1, 2, 4, 8, 16, 32, 64, com.jrpetty.mcassistant.TimeSpeed.MAX};
        int bw = (W - PAD * 2 - 34 - (speeds.length - 1) * 2) / speeds.length;
        for (int i = 0; i < speeds.length; i++) {
            int f = speeds[i];
            Button b = Button.builder(Component.literal(com.jrpetty.mcassistant.TimeSpeed.label(f)),
                    btn -> TimeSpeedClient.ask(0, f))
                .bounds(left + PAD + 34 + i * (bw + 2), top + H - PAD - 40, bw, 16).build();
            b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(f == 1
                ? "Time at its normal pace"
                : f >= com.jrpetty.mcassistant.TimeSpeed.MAX ? "Time as fast as this machine can run it"
                : "Time runs " + f + " times faster, to watch the village grow")));
            this.addRenderableWidget(b);
        }
    }

    private int visible() {
        return (H - PAD * 2 - 18 - 22 - 22) / (this.font.lineHeight + 1);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        scroll = Math.max(0, Math.min(Math.max(0, lines.size() - visible()), scroll - (int) Math.signum(dy) * 3));
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        Ui.panel(g, left, top, W, H, 22);
        g.drawString(this.font, this.title, left + PAD, top + 7, Ui.INK, false);
        int y = top + 26;
        int shown = visible();
        for (int i = scroll; i < Math.min(lines.size(), scroll + shown); i++) {
            g.drawString(this.font, lines.get(i), left + PAD, y, Ui.INK, false);
            y += this.font.lineHeight + 1;
        }
        g.drawString(this.font, "Time", left + PAD, top + H - PAD - 36,
            TimeSpeedClient.factor() > 1 ? 0xFFFFD866 : Ui.MUTED, false);
        if (lines.size() > shown) {
            g.drawString(this.font, (scroll + 1) + "–" + Math.min(lines.size(), scroll + shown) + " of " + lines.size()
                + " (scroll)", left + PAD, top + H - PAD - 13, Ui.MUTED, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
