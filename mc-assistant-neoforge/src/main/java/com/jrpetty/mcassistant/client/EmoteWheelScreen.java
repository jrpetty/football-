package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.entity.FolkAction.Act;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * [anim] The emote wheel (V, by default): the player's emotes round a ring, the one under the pointer lit, played on a
 * click or when the key is let go (hold V, point, let go), or by its number. A handshake goes to the folk the player was
 * looking at. Escape closes it with nothing done. The game goes on behind it.
 */
public final class EmoteWheelScreen extends Screen {

    /** The ring is an ellipse, wider than tall, so fourteen boxes sit round it without touching. */
    private static final int RING_X = 150, RING_Y = 96, BOX_W = 66, BOX_H = 16;
    private static final int PANEL = 0xC0101018, LIT = 0xF0E8B84A, RIM = 0xFF3A3440, INK = 0xFFF4EEDC, DARK_INK = 0xFF201810;
    private int hovered = -1;

    public EmoteWheelScreen() {
        super(Component.literal("Emotes"));
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        // No blur and no dark: the player sees the world, and whoever it is about to wave to.
    }

    private Act[] emotes() { return PlayerEmotesClient.emotes(); }

    /** [ui29] Where the boxes were last laid, and how wide: in two columns (a small GUI) the box under the mouse is the one. */
    private int[][] laid = new int[0][];
    private int[] laidW = new int[0];

    private int under(double mx, double my) {
        if (ScreenFit.isColumns(laid, width)) {
            for (int i = 0; i < laid.length; i++) {
                if (Math.abs(mx - laid[i][0]) * 2 <= laidW[i] && Math.abs(my - laid[i][1]) * 2 <= BOX_H + 2) return i;
            }
            return -1;
        }
        double dx = mx - width / 2.0, dy = my - height / 2.0;
        if (dx * dx + dy * dy < 22 * 22) return -1;
        int n = emotes().length;
        double ang = Math.atan2(dx / ScreenFit.ringRadiusX(width, RING_X), -dy / ScreenFit.ringRadiusY(height, RING_Y));           // round the ellipse, as the boxes are
        if (ang < 0) ang += Math.PI * 2;
        return (int) Math.round(ang / (Math.PI * 2 / n)) % n;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        hovered = under(mx, my);
        int cx = width / 2, cy = height / 2;
        Act[] e = emotes();
        // [ui29] each box as wide as its words, the ring kept on the screen and off the hotbar, and a box that would land on
        // one already drawn pushed out along its spoke until it is clear (ScreenFit.ring; the long names overlapped at every scale)
        String[] words = new String[e.length];
        int[] bw = new int[e.length];
        for (int i = 0; i < e.length; i++) {
            words[i] = (i < 9 ? (i + 1) + " " : "") + PlayerEmotesClient.words(e[i]);
            bw[i] = Math.max(BOX_W, font.width(words[i]) + 10);
        }
        int[][] at = ScreenFit.ring(width, height, bw, BOX_H, RING_X, RING_Y);
        laid = at;
        laidW = bw;
        for (int i = 0; i < e.length; i++) {
            int x = at[i][0], y = at[i][1];
            boolean lit = i == hovered;
            int x0 = x - bw[i] / 2, y0 = y - BOX_H / 2;
            g.fill(x0 - 1, y0 - 1, x0 + bw[i] + 1, y0 + BOX_H + 1, RIM);
            g.fill(x0, y0, x0 + bw[i], y0 + BOX_H, lit ? LIT : PANEL);
            g.drawString(font, words[i], x - font.width(words[i]) / 2, y - 4, lit ? DARK_INK : INK, false);
        }
        String mid = hovered >= 0 ? PlayerEmotesClient.words(e[hovered]) : "Emotes";
        int w = Math.max(64, font.width(mid) + 16);
        g.fill(cx - w / 2 - 1, cy - 11, cx + w / 2 + 1, cy + 11, RIM);
        g.fill(cx - w / 2, cy - 10, cx + w / 2, cy + 10, PANEL);
        g.drawCenteredString(font, mid, cx, cy - 4, hovered >= 0 ? LIT : INK);
        g.drawCenteredString(font, "Click one, or press its number", cx, cy + 16, 0xE0F4EEDC);   // [ui29] inside the ring, off the hotbar
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int i = under(mx, my);
        if (i >= 0) {
            play(i);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key >= org.lwjgl.glfw.GLFW.GLFW_KEY_1 && key <= org.lwjgl.glfw.GLFW.GLFW_KEY_9) {
            int i = key - org.lwjgl.glfw.GLFW.GLFW_KEY_1;
            if (i < emotes().length) {
                play(i);
                return true;
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean keyReleased(int key, int scan, int mods) {
        if (PlayerEmotesClient.WHEEL.matches(key, scan) && hovered >= 0) {
            play(hovered);
            return true;
        }
        return super.keyReleased(key, scan, mods);
    }

    private void play(int i) {
        PlayerEmotesClient.send(emotes()[i]);
        onClose();
    }
}
