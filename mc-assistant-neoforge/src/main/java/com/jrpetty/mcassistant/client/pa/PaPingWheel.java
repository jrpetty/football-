package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.client.ScreenFit;
import com.jrpetty.mcassistant.pa.PaPings;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.List;

/**
 * [pa33c-2b] The ping wheel: hold the ping key on something and a ring of what can be done with it opens (chop that,
 * mine the vein, attack that, go there, mark an area...), each in its kind's colour and shape. Point and let go, click,
 * or press its number; Esc or letting go in the middle picks nothing (the ping stays a plain marker).
 */
public final class PaPingWheel extends Screen {

    private static final int RING_X = 140, RING_Y = 86, BOX_H = 18;
    private static final int PANEL = 0xD0102A2E, RIM = 0xFF1F8A7A, INK = 0xFFF2F7F6;
    private final PaPingClient.Aim aim;
    @Nullable private final PaPingClient.Aim corner;
    private final boolean held;
    private final List<PaPings.Option> options;
    private final long opened = System.currentTimeMillis();
    private int hovered = -1;
    private int[][] laid = new int[0][];
    private int[] laidW = new int[0];

    public PaPingWheel(PaPingClient.Aim aim, @Nullable PaPingClient.Aim corner, boolean held) {
        super(Component.literal("Ping"));
        this.aim = aim;
        this.corner = corner;
        this.held = held;
        this.options = PaPings.options(corner != null ? PaPings.Target.AREA : aim.target());
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) { }

    private int under(double mx, double my) {
        for (int i = 0; i < laid.length; i++) {
            if (Math.abs(mx - laid[i][0]) * 2 <= laidW[i] && Math.abs(my - laid[i][1]) * 2 <= BOX_H + 2) return i;
        }
        if (ScreenFit.isColumns(laid, width)) return -1;
        double dx = mx - width / 2.0, dy = my - height / 2.0;
        if (dx * dx + dy * dy < 28 * 28 || options.isEmpty()) return -1;
        double ang = Math.atan2(dx / ScreenFit.ringRadiusX(width, RING_X), -dy / ScreenFit.ringRadiusY(height, RING_Y));
        if (ang < 0) ang += Math.PI * 2;
        return (int) Math.round(ang / (Math.PI * 2 / options.size())) % options.size();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        int n = options.size();
        int[] bw = new int[n];
        for (int i = 0; i < n; i++) bw[i] = Math.max(78, font.width((i + 1) + "  " + options.get(i).label()) + 30);
        float grow = Math.min(1F, (System.currentTimeMillis() - opened) / 120F);       // a quick open, not a pop
        laid = ScreenFit.ring(width, height, bw, BOX_H, Math.round(RING_X * (0.6F + 0.4F * grow)), Math.round(RING_Y * (0.6F + 0.4F * grow)));
        laidW = bw;
        hovered = under(mx, my);
        int cx = width / 2, cy = height / 2;
        for (int i = 0; i < n; i++) {
            PaPings.Option o = options.get(i);
            int x = laid[i][0], y = laid[i][1];
            boolean lit = i == hovered;
            int x0 = x - bw[i] / 2, y0 = y - BOX_H / 2;
            g.fill(x0 - 1, y0 - 1, x0 + bw[i] + 1, y0 + BOX_H + 1, lit ? o.sort().colour : RIM);
            g.fill(x0, y0, x0 + bw[i], y0 + BOX_H, lit ? (o.sort().colour & 0x00FFFFFF) | 0xF0000000 : PANEL);
            PaPingClient.shape(g, o.sort().shape, x0 + 10, y, o.sort().colour);
            g.drawString(font, (i + 1) + "  " + o.label(), x0 + 20, y - 4, lit ? 0xFFFFFFFF : INK, false);
        }
        String mid = hovered >= 0 ? options.get(hovered).label() : aim.label();
        String sub = corner != null ? "the area between the corners" : hovered >= 0 ? "for " + PaClient.name() : "let go here: just a marker";
        int w = Math.max(96, Math.max(font.width(mid), font.width(sub)) + 16);
        g.fill(cx - w / 2 - 1, cy - 13, cx + w / 2 + 1, cy + 13, RIM);
        g.fill(cx - w / 2, cy - 12, cx + w / 2, cy + 12, PANEL);
        g.drawCenteredString(font, mid, cx, cy - 9, hovered >= 0 ? 0xFF8FE3D3 : INK);
        g.drawCenteredString(font, sub, cx, cy + 2, 0xFF9FC9C2);
    }

    private void pick(int i) {
        onClose();
        if (i < 0 || i >= options.size()) {
            if (corner == null) PaPingClient.tapFromWheel(aim);                        // nothing chosen: a plain marker
            return;
        }
        PaPingClient.choose(aim, options.get(i), corner);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) { pick(under(mx, my)); return true; }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (held && PaClient.PING.matchesMouse(button)) { pick(under(mx, my)); return true; }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean keyReleased(int key, int scan, int mods) {
        if (held && PaClient.PING.matches(key, scan)) { pick(hovered); return true; }
        return super.keyReleased(key, scan, mods);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9 && key - GLFW.GLFW_KEY_1 < options.size()) { pick(key - GLFW.GLFW_KEY_1); return true; }
        if (key == GLFW.GLFW_KEY_ESCAPE && corner != null) PaPingClient.cancelLast();
        return super.keyPressed(key, scan, mods);
    }
}
