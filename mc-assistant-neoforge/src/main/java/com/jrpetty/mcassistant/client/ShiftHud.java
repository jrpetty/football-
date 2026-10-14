package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.ArrayList;
import java.util.List;

import static com.jrpetty.mcassistant.client.UiStyle.*;

/**
 * [earning] The shift on the screen: a small card down the left while a shift is on (the role and town, the time
 * left, the task in plain words, which way and how far its target is, the par clock or the stove's, tasks done, pay,
 * tips and the rating, the skill, the last task's stars and why), its "how this job works" lines the first time, the
 * summary card when it ends; the toasts of each payment in the top right; and the Earnings page's marker as a line
 * at the top with its way and distance. The words are wrapped once when the server's state changes; a frame only
 * draws.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT)
public final class ShiftHud {

    private ShiftHud() {}

    static final int W = 176, PAD = 6;
    /** The card's ground (dark, see-through) and its stars' gold. */
    static final int GROUND = 0xC8101418, GOLD = 0xFFF2C14E, SOFT = 0xFFB9C2CC;
    static final String[] ARROWS = { "↑", "↗", "→", "↘", "↓", "↙", "←", "↖" };

    private static boolean dirty = true;
    private static List<FormattedCharSequence> taskLines = List.of(), howLines = List.of();
    private static String header = "", stats = "", skill = "", last = "";

    static void dirty() {
        dirty = true;
    }

    private static void layout(Font font, CompoundTag h) {
        dirty = false;
        header = h.getString("title") + " · " + h.getString("town");
        taskLines = TextCache.splitPlain(font, h.getString("task"), W - 2 * PAD);
        int tenths = h.getInt("starsX10");
        stats = "Tasks " + h.getInt("tasks") + "   Pay " + h.getInt("pay") + "c   Tips " + h.getInt("tips") + "c"
            + (h.getInt("tasks") > 0 ? "   ★ " + tenths / 10 + "." + tenths % 10 : "");
        skill = "Skill: " + h.getString("level");
        last = h.getString("last");
        List<FormattedCharSequence> how = new ArrayList<>();
        ListTag hl = h.getList("howTo", Tag.TAG_STRING);
        for (int i = 0; i < hl.size(); i++) how.addAll(TextCache.splitPlain(font, "• " + hl.getString(i), W - 2 * PAD));
        howLines = how;
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;
        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;
        toasts(g, font);
        marker(g, font, mc);
        CompoundTag h = EarnClient.hud;
        if (h != null) {
            if (dirty) layout(font, h);
            card(g, font, mc, h);
        }
        if (EarnClient.summary != null) summary(g, font, EarnClient.summary);
    }

    // ------------------------------------------------------------------ the shift card

    private static void card(GuiGraphics g, Font font, Minecraft mc, CompoundTag h) {
        int x = 4, y = 34;
        long now = EarnClient.serverNow();
        boolean cooking = h.contains("readyAt");
        int lines = taskLines.size();
        // The rows as drawn below: the header, the task, the bar, the state, the stats, the skill, the last task.
        int height = PAD + 14 + lines * 10 + 2 + 8 + 12 + 10 + 12 + (last.isEmpty() ? 0 : 10) + PAD - 2;
        g.fill(x, y, x + W, y + height, GROUND);
        g.fill(x, y, x + 2, y + height, ACCENT_FILL);
        int cy = y + PAD;
        // The role and town; the time left on the right.
        long left = Math.max(0, h.getLong("ends") - now) / 20;
        String clock = left / 60 + ":" + (left % 60 < 10 ? "0" : "") + left % 60;
        int cw = font.width(clock);
        g.drawString(font, clip(font, header, W - 2 * PAD - cw - 4), x + PAD, cy, TEXT_ON_DARK, true);
        g.drawString(font, clock, x + W - PAD - cw, cy, left < 30 ? WARNING_FILL : SOFT, false);
        cy += 14;
        for (FormattedCharSequence s : taskLines) {
            g.drawString(font, s, x + PAD, cy, 0xFFFFFFFF, false);
            cy += 10;
        }
        cy += 2;
        // Which way and how far: the target, else the folk.
        int[] t = h.getIntArray("target");
        String way = "";
        if (t.length == 3) way = direction(mc, t[0] + 0.5, t[2] + 0.5) + " " + (int) Math.sqrt(mc.player.distanceToSqr(t[0] + 0.5, t[1], t[2] + 0.5)) + "m";
        // The clock against par (or the stove's).
        int barW = W - 2 * PAD - (way.isEmpty() ? 0 : font.width(way) + 6);
        if (cooking) {
            long ready = h.getLong("readyAt"), burn = h.getLong("burnAt");
            long from = h.getLong("taskStarted");
            if (now < ready) {
                bar(g, x + PAD, cy, barW, 6, Mth.clamp((now - from) / (float) Math.max(1, ready - from), 0, 1), WARNING_FILL);
            } else {
                bar(g, x + PAD, cy, barW, 6, 1F - Mth.clamp((now - ready) / (float) Math.max(1, burn - ready), 0, 1), now <= burn ? SUCCESS_FILL : DANGER_FILL);
            }
        } else if (h.contains("par")) {
            float f = (now - h.getLong("taskStarted")) / (float) Math.max(1, h.getLong("par"));
            bar(g, x + PAD, cy, barW, 6, Mth.clamp(f, 0, 1), f <= 1 ? SUCCESS_FILL : f <= 2 ? WARNING_FILL : DANGER_FILL);
        }
        if (!way.isEmpty()) g.drawString(font, way, x + W - PAD - font.width(way), cy - 1, GOLD, false);
        cy += 8;
        if (cooking) {
            String state = now < h.getLong("readyAt") ? "On the heat: " + (h.getLong("readyAt") - now) / 20 + "s" : now <= h.getLong("burnAt") ? "READY: take it off now!" : "Burning!";
            g.drawString(font, state, x + PAD, cy, now < h.getLong("readyAt") ? SOFT : now <= h.getLong("burnAt") ? 0xFF9BE07A : 0xFFFF7A6A, false);
        } else {
            g.drawString(font, h.contains("par") ? "Par " + h.getLong("par") / 20 + "s" + (h.getInt("mistakes") > 0 ? " · " + h.getInt("mistakes") + " mistake" + (h.getInt("mistakes") == 1 ? "" : "s") : "") : "", x + PAD, cy, SOFT, false);
        }
        cy += 12;
        g.drawString(font, stats, x + PAD, cy, TEXT_ON_DARK, false);
        cy += 10;
        g.drawString(font, skill, x + PAD, cy, SOFT, false);
        int sw = font.width(skill);
        bar(g, x + PAD + sw + 6, cy + 2, W - 2 * PAD - sw - 6, 4, h.getFloat("progress"), INFO_FILL);
        cy += 12;
        if (!last.isEmpty()) {
            boolean fresh = now - h.getLong("lastAt") < 200;
            g.drawString(font, clip(font, last, W - 2 * PAD), x + PAD, cy, fresh ? (h.getInt("lastStars") >= 4 ? GOLD : h.getInt("lastStars") >= 3 ? SOFT : 0xFFFF9A6A) : 0xFF8A939C, false);
        }
        // The first time: how this job works, under the card.
        if (!howLines.isEmpty()) {
            int hy = y + height + 4;
            int hh = PAD * 2 + 10 + howLines.size() * 10;
            g.fill(x, hy, x + W, hy + hh, GROUND);
            g.fill(x, hy, x + 2, hy + hh, INFO_FILL);
            g.drawString(font, "How this job works", x + PAD, hy + PAD, GOLD, false);
            int ly = hy + PAD + 12;
            for (FormattedCharSequence s : howLines) {
                g.drawString(font, s, x + PAD, ly, TEXT_ON_DARK, false);
                ly += 10;
            }
        }
    }

    /** An arrow from where the player faces to the place. */
    private static String direction(Minecraft mc, double tx, double tz) {
        double dx = tx - mc.player.getX(), dz = tz - mc.player.getZ();
        double to = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = Mth.wrapDegrees(to - mc.player.getYRot());
        int i = Math.floorMod((int) Math.round(rel / 45.0), 8);
        return ARROWS[i];
    }

    // ------------------------------------------------------------------ the summary

    private static void summary(GuiGraphics g, Font font, CompoundTag s) {
        int w = 220, x = (g.guiWidth() - w) / 2, y = 30;
        String verdict = s.getString("verdict");
        List<FormattedCharSequence> vl = verdict.isEmpty() ? List.of() : TextCache.splitPlain(font, verdict, w - 2 * PAD);
        int h = PAD * 2 + 12 + 12 + 10 + 10 + 10 + vl.size() * 10;
        g.fill(x, y, x + w, y + h, GROUND);
        g.fill(x, y, x + w, y + 2, ACCENT_FILL);
        int cy = y + PAD + 2;
        g.drawString(font, clip(font, s.getString("title"), w - 2 * PAD), x + PAD, cy, TEXT_ON_DARK, true);
        cy += 12;
        int tenths = s.getInt("starsX10");
        String stars = "★".repeat(Math.max(0, Math.min(5, Math.round(tenths / 10f)))) + "☆".repeat(5 - Math.max(0, Math.min(5, Math.round(tenths / 10f))));
        g.drawString(font, stars + "  " + tenths / 10 + "." + tenths % 10 + " average over " + s.getInt("tasks") + " tasks", x + PAD, cy, GOLD, false);
        cy += 12;
        g.drawString(font, "Pay " + s.getInt("pay") + "c   Tips " + s.getInt("tips") + "c   Total " + (s.getInt("pay") + s.getInt("tips")) + "c", x + PAD, cy, TEXT_ON_DARK, false);
        cy += 10;
        String sk = "+" + s.getInt("xp") + " skill: " + s.getString("level");
        g.drawString(font, sk, x + PAD, cy, SOFT, false);
        bar(g, x + PAD + font.width(sk) + 6, cy + 2, w - 2 * PAD - font.width(sk) - 6, 4, s.getFloat("progress"), INFO_FILL);
        cy += 10;
        g.drawString(font, clip(font, s.getString("why"), w - 2 * PAD), x + PAD, cy, 0xFF8A939C, false);
        cy += 10;
        for (FormattedCharSequence l : vl) {
            g.drawString(font, l, x + PAD, cy, 0xFFFF7A6A, false);
            cy += 10;
        }
    }

    // ------------------------------------------------------------------ toasts and the marker

    private static void toasts(GuiGraphics g, Font font) {
        if (EarnClient.TOASTS.isEmpty()) return;
        int y = 36, right = g.guiWidth() - 4;
        for (EarnClient.Toast t : EarnClient.TOASTS) {
            long left = t.until() - EarnClient.ticks;
            float a = Mth.clamp(left / 20f, 0, 1);
            String text = clip(font, t.text(), 200);
            int w = font.width(text) + 2 * PAD;
            g.fill(right - w, y, right, y + 14, alpha(GROUND, a));
            g.fill(right - w, y, right - w + 2, y + 14, alpha(GOLD, a));
            g.drawString(font, text, right - w + PAD, y + 3, alpha(0xFFFFE9A8, a), false);
            y += 16;
        }
    }

    private static void marker(GuiGraphics g, Font font, Minecraft mc) {
        int[] m = EarnClient.marker;
        if (m == null) return;
        String text = "Marker: " + EarnClient.markerLabel + "  " + direction(mc, m[0] + 0.5, m[2] + 0.5) + " "
            + (int) Math.sqrt(mc.player.distanceToSqr(m[0] + 0.5, m[1], m[2] + 0.5)) + "m";
        text = clip(font, text, 300);
        int w = font.width(text) + 2 * PAD, x = (g.guiWidth() - w) / 2, y = 4;
        g.fill(x, y, x + w, y + 14, GROUND);
        g.drawString(font, text, x + PAD, y + 3, GOLD, false);
    }
}
