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
        if (mc.screen instanceof TalkScreen talk && talk.entityId() == reply.entityId()) {
            talk.update(reply);
        } else if (reply.open()) {
            Entity e = mc.level.getEntity(reply.entityId());
            if (e != null) mc.setScreen(new TalkScreen(reply));
        }
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
