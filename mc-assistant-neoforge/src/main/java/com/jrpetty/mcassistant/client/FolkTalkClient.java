package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.net.FolkReplyPayload;
import com.jrpetty.mcassistant.net.FolkSpeechPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;

/**
 * The client's half of talking with folk: it opens (or updates) the talk screen
 * when a folk answers you, and keeps what each folk is saying out loud so its
 * renderer can draw the bubble over its head.
 */
public final class FolkTalkClient {

    private FolkTalkClient() {}

    /** A line being said, and the game tick it stops being said. */
    public record Said(String text, long until) {}

    private static final Map<Integer, Said> SAYING = new HashMap<>();

    public static void reply(FolkReplyPayload reply) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        guiScale(mc, reply.context());
        if (mc.screen instanceof TalkScreen talk && talk.entityId() == reply.entityId()) {
            talk.update(reply);
        } else if (reply.open()) {
            Entity e = mc.level.getEntity(reply.entityId());
            if (e != null) mc.setScreen(new TalkScreen(reply));
        }
    }

    /**
     * [talk-ui] An operator's /village talkview asks for the GUI at a scale ("guiscale=3" in the reply's context) and
     * the window at a size ("window=640x480"), so the pictures can show the talk screen small. Only ever sent by that
     * command (with its "show="); a player's own talk never carries it.
     */
    private static void guiScale(Minecraft mc, String context) {
        if (context == null || !context.contains("show=")) return;
        String window = flag(context, "window");
        int x = window.indexOf('x');
        if (x > 0) {
            try {
                int w = Integer.parseInt(window.substring(0, x)), h = Integer.parseInt(window.substring(x + 1));
                if (w >= 320 && h >= 240 && w <= 4096 && h <= 4096) {
                    org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), w, h);
                }
            } catch (NumberFormatException ignored) {
                // not a size: left as it is
            }
        }
        try {
            String s = flag(context, "guiscale");
            if (s.isEmpty()) return;
            int scale = Integer.parseInt(s);
            if (scale < 0 || scale > 6 || mc.options.guiScale().get() == scale) return;
            mc.options.guiScale().set(scale);
            mc.resizeDisplay();
        } catch (NumberFormatException ignored) {
            // not a number: left as it is
        }
    }

    /** A {@code key=value} flag's value in a context line, or "". */
    private static String flag(String context, String key) {
        int at = context.indexOf(key + "=");
        if (at < 0) return "";
        int end = context.indexOf(' ', at);
        return context.substring(at + key.length() + 1, end < 0 ? context.length() : end);
    }

    public static void speech(FolkSpeechPayload said) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        SAYING.put(said.entityId(), new Said(said.text(), mc.level.getGameTime() + said.ticks()));
        if (SAYING.size() > 64) {
            long now = mc.level.getGameTime();
            SAYING.values().removeIf(s -> s.until() < now);
        }
    }

    /** What this folk is saying right now, if anything. */
    public static Said saying(int entityId, long now) {
        Said s = SAYING.get(entityId);
        if (s == null) return null;
        if (s.until() < now) {
            SAYING.remove(entityId);
            return null;
        }
        return s;
    }
}
