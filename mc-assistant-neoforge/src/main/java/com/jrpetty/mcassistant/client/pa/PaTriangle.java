package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.client.UiStyle;
import com.jrpetty.mcassistant.pa.PaLines;
import com.jrpetty.mcassistant.pa.PaPersona;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * [pa33b] The personality triangle in the About tab: Serious at the top, Funny bottom left, Gloomy bottom right.
 * Click or drag the dot anywhere inside (arrow keys nudge it once it has been clicked); the region's name and a
 * sample line show underneath and change as it moves; presets snap it. Sent to the server when let go.
 */
final class PaTriangle {

    private float funny = (float) PaPersona.DEFAULT_FUNNY, gloomy = (float) PaPersona.DEFAULT_GLOOMY;
    private boolean dragging, focused, dirty;
    private int ax, ay, bx, by, cx, cy;          // corners as last drawn
    private int sampleBucket = -1;
    private String sample = "";

    /** The server's numbers, unless the player is moving the dot. */
    void sync(CompoundTag set) {
        if (dragging || dirty || !set.contains("funny")) return;
        funny = set.getFloat("funny");
        gloomy = set.getFloat("gloomy");
    }

    PaPersona.Tri tri() {
        return PaPersona.Tri.of(funny, gloomy);
    }

    /** Draws it (size = the triangle's width) and returns the y under it. */
    int draw(GuiGraphics g, Font font, int x, int y, int size, int mx, int my) {
        int h = (int) Math.round(size * 0.866);
        ax = x + size / 2; ay = y + 10;          // Serious
        bx = x; by = y + 10 + h;                  // Funny
        cx = x + size; cy = y + 10 + h;           // Gloomy
        // the field, shaded by blend (cells of 2 GUI pixels)
        for (int py = ay; py < by; py += 2) {
            for (int px = bx; px < cx; px += 2) {
                PaPersona.Tri w = PaPersona.fromPoint(px + 1, py + 1, ax, ay, bx, by, cx, cy);
                double raw = edgeSum(px + 1, py + 1);
                if (raw > 1.001) continue;
                g.fill(px, py, px + 2, py + 2, blend(w));
            }
        }
        // corner names
        g.drawString(font, "Serious", ax - font.width("Serious") / 2, y, UiStyle.TEXT_MUTED, false);
        g.drawString(font, "Funny", bx, by + 3, UiStyle.TEXT_MUTED, false);
        g.drawString(font, "Gloomy", cx - font.width("Gloomy"), cy + 3, UiStyle.TEXT_MUTED, false);
        // the dot
        double[] p = PaPersona.toPoint(tri(), ax, ay, bx, by, cx, cy);
        int dx = (int) Math.round(p[0]), dy = (int) Math.round(p[1]);
        g.fill(dx - 3, dy - 3, dx + 4, dy + 4, 0xFF101010);
        g.fill(dx - 2, dy - 2, dx + 3, dy + 3, focused ? 0xFFFFFFFF : 0xFFF2E8B0);
        int under = by + 14;
        PaPersona.Tri t = tri();
        g.drawString(font, t.label(), x, under, UiStyle.TEXT, false);
        under += 10;
        String mix = pct(t.serious()) + " serious · " + pct(t.funny()) + " funny · " + pct(t.gloomy()) + " gloomy";
        g.drawString(font, UiStyle.clip(font, mix, size + 60), x, under, UiStyle.TEXT_FAINT, false);
        under += 11;
        int bucket = (int) (funny * 20) * 100 + (int) (gloomy * 20);
        if (bucket != sampleBucket) {
            sampleBucket = bucket;
            java.util.Random r = new java.util.Random(bucket * 31L + 7);
            List<String> l = PaPersona.linesFor("say.ok", t, r::nextInt);
            String s = PaPersona.from(l, r::nextInt, null);
            sample = s == null ? PaPersona.from(PaLines.get("say.ok@serious"), r::nextInt, null) : s;
            if (sample == null) sample = "On it.";
        }
        List<FormattedCharSequence> lines = font.split(Component.literal("“" + sample + "”"), size + 60);
        for (FormattedCharSequence l : lines) { g.drawString(font, l, x, under, PaScreen.BRAND, false); under += 10; }
        return under + 2;
    }

    private double edgeSum(double px, double py) {
        double det = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy);
        double wa = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) / det;
        double wb = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) / det;
        double wc = 1 - wa - wb;
        return Math.abs(wa) + Math.abs(wb) + Math.abs(wc);
    }

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }

    /** Serious slate, Funny warm gold, Gloomy plum, mixed by weight. */
    static int blend(PaPersona.Tri w) {
        int[] s = {0x6C, 0x7F, 0x8E}, f = {0xE8, 0xB8, 0x3A}, gl = {0x6A, 0x4C, 0x8C};
        int r = (int) (s[0] * w.serious() + f[0] * w.funny() + gl[0] * w.gloomy());
        int gg = (int) (s[1] * w.serious() + f[1] * w.funny() + gl[1] * w.gloomy());
        int b = (int) (s[2] * w.serious() + f[2] * w.funny() + gl[2] * w.gloomy());
        return 0xFF000000 | (r << 16) | (gg << 8) | b;
    }

    boolean inside(double mx, double my) {
        return mx >= bx - 4 && mx <= cx + 4 && my >= ay - 4 && my <= by + 4 && edgeSum(mx, my) <= 1.15;
    }

    boolean click(double mx, double my) {
        if (!inside(mx, my)) { focused = false; return false; }
        dragging = true;
        focused = true;
        moveTo(mx, my);
        return true;
    }

    boolean drag(double mx, double my) {
        if (!dragging) return false;
        moveTo(mx, my);
        return true;
    }

    boolean release() {
        if (!dragging) return false;
        dragging = false;
        send();
        return true;
    }

    private void moveTo(double mx, double my) {
        PaPersona.Tri t = PaPersona.fromPoint(mx, my, ax, ay, bx, by, cx, cy);
        funny = (float) t.funny();
        gloomy = (float) t.gloomy();
        dirty = true;
    }

    /** Arrow keys nudge the dot (once it has been clicked). */
    boolean key(int key) {
        if (!focused) return false;
        double[] p = PaPersona.toPoint(tri(), ax, ay, bx, by, cx, cy);
        double step = 3;
        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> p[0] -= step;
            case GLFW.GLFW_KEY_RIGHT -> p[0] += step;
            case GLFW.GLFW_KEY_UP -> p[1] -= step;
            case GLFW.GLFW_KEY_DOWN -> p[1] += step;
            default -> { return false; }
        }
        moveTo(p[0], p[1]);
        send();
        return true;
    }

    void preset(String name) {
        PaPersona.Tri t = PaPersona.preset(name);
        funny = (float) t.funny();
        gloomy = (float) t.gloomy();
        dirty = true;
        send();
    }

    private void send() {
        CompoundTag a = new CompoundTag();
        a.putFloat("funny", funny);
        a.putFloat("gloomy", gloomy);
        PaClient.send("settings", a);
        dirty = false;
    }
}
