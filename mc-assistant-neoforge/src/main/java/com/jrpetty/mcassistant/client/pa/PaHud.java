package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.client.ScreenFit;
import com.jrpetty.mcassistant.pa.PaClientConfig;
import com.jrpetty.mcassistant.pa.PlayerAssistantEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * [pa33] On the screen while playing: a small line saying what the assistant is doing ("Chopping logs 12/16", with
 * its bar), the listening light and the words as they are heard, a subtitle of what it last said when it is out of
 * sight, and the system's notes. Kept small, top left above the mod's other panels, and the listening light above
 * the hotbar. Hidden with F1 and while the panel is open.
 */
final class PaHud {

    private PaHud() {}

    static final int BRAND = 0xFF2BB5A0, BRAND_DARK = 0xE0102A2E, INK = 0xFFF2F7F6, SOFT = 0xFFA9C9C4;

    static void draw(GuiGraphics g, Minecraft mc) {
        Font font = mc.font;
        int w = g.guiWidth(), h = g.guiHeight();
        long ms = System.currentTimeMillis();
        // what it is doing
        if (PaClientConfig.get(PaClientConfig.HUD, true) && PaClient.has()) {
            PlayerAssistantEntity a = PaClient.mine();
            String st = a != null ? a.state() : PaClient.state.getString("state");
            boolean dead = PaClient.state.getBoolean("dead");
            if (dead) st = "dead|Knocked out|-1";
            if (!st.isEmpty()) {
                String[] f = st.split("\\|", -1);
                String line = f.length > 1 ? f[1] : "";
                int pct = f.length > 2 ? parse(f[2]) : -1;
                if (!line.isEmpty() && !(f[0].equals("follow") && a != null && a.distanceTo(mc.player) < 12 && !PaClient.state.getBoolean("busyHud"))) {
                    String name = PaClient.name();
                    String text = name + ": " + line;
                    int tw = Math.min(font.width(text), 200);
                    int x = 4, y = 4, bw = tw + 22;
                    g.fill(x, y, x + bw, y + (pct >= 0 ? 17 : 14), BRAND_DARK);
                    g.fill(x, y, x + 2, y + (pct >= 0 ? 17 : 14), dead ? 0xFFC2452F : BRAND);
                    PaIcons.draw(g, f[0], x + 4, y + 2);
                    g.drawString(font, com.jrpetty.mcassistant.client.UiStyle.clip(font, text, 200), x + 17, y + 3, INK, false);
                    if (pct >= 0) {
                        g.fill(x + 17, y + 13, x + bw - 4, y + 15, 0xFF33494C);
                        g.fill(x + 17, y + 13, x + 17 + (bw - 21) * Math.min(100, pct) / 100, y + 15, BRAND);
                    }
                }
            }
        }
        int base = h - ScreenFit.HOTBAR - 34;
        // listening
        if (PaVoice.listening || PaVoice.hearing) {
            String label = PaVoice.listening ? "Listening... (let go to send)" : "Hearing you...";
            String said = PaVoice.transcript;
            int bw = Math.max(font.width(label), font.width(said)) + 30;
            int x = (w - bw) / 2, y = base - 14;
            g.fill(x, y, x + bw, y + (said.isEmpty() ? 14 : 25), BRAND_DARK);
            float lv = PaVoice.level();
            int dot = PaVoice.listening ? (0xFF000000 | (int) (0xC0 + 0x3F * lv) << 16 | 0x30 << 8 | 0x30) : BRAND;
            g.fill(x + 6, y + 4, x + 12, y + 10, dot);
            for (int i = 0; i < 4; i++) {
                int bh = (int) (2 + 8 * Math.min(1.0, lv * (1.0 + i * 0.4) * (0.6 + 0.4 * Math.sin(ms / 90.0 + i))));
                g.fill(x + 15 + i * 3, y + 11 - bh, x + 17 + i * 3, y + 11, PaVoice.listening ? BRAND : SOFT);
            }
            g.drawString(font, label, x + 30, y + 3, INK, false);
            if (!said.isEmpty()) g.drawString(font, "“" + said + "”", x + 8, y + 14, SOFT, false);
            base -= 30;
        } else if (!PaVoice.transcript.isEmpty() && ms - PaVoice.transcriptAt < 3000) {
            String s = "You said: “" + PaVoice.transcript + "”";
            g.drawCenteredString(font, s, w / 2, base, SOFT);
            base -= 12;
        }
        // a note about voice, or from the system
        String note = !PaVoice.status.isEmpty() && ms - PaVoice.statusAt < 6000 ? PaVoice.status
            : !PaClient.note.isEmpty() && ms - PaClient.noteAt < 5000 ? PaClient.note : "";
        if (!note.isEmpty()) {
            int bw = Math.min(w - 20, font.width(note) + 12);
            g.fill((w - bw) / 2, base - 2, (w + bw) / 2, base + 10, BRAND_DARK);
            g.drawCenteredString(font, com.jrpetty.mcassistant.client.UiStyle.clip(font, note, bw - 8), w / 2, base, INK);
            base -= 14;
        }
        // what it said, when it is out of sight
        if (ms < PaClient.subtitleUntil) {
            PlayerAssistantEntity a = PaClient.mine();
            boolean seen = a != null && a.distanceTo(mc.player) < 14 && mc.player.hasLineOfSight(a);
            if (!seen) {
                var lines = font.split(net.minecraft.network.chat.Component.literal(PaClient.subtitle), Math.min(320, w - 40));
                int n = Math.min(3, lines.size());
                int bw = 0;
                for (int i = 0; i < n; i++) bw = Math.max(bw, font.width(lines.get(i)));
                int y = base - n * 10;
                g.fill((w - bw) / 2 - 5, y - 3, (w + bw) / 2 + 5, y + n * 10 + 1, 0xB0000000);
                for (int i = 0; i < n; i++) g.drawString(font, lines.get(i), (w - font.width(lines.get(i))) / 2, y + i * 10, 0xFFE8F6F3, false);
            }
        }
    }

    static int parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
