package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.util.Mth;

import java.util.HashMap;
import java.util.Map;

/**
 * [quests] The client's half of the quests: the marks over the folk near the player ("!" a folk with work to give,
 * "?" one a step of its quests waits on), drawn over their heads as a name tag is; the quest buttons the folk the
 * player is talking to last sent (for the talk screen); and the journal's screen, opened when its pages come.
 */
public final class QuestClient {

    private QuestClient() {}

    private static final Map<Integer, Character> MARKS = new HashMap<>();
    private static final Map<Integer, String> CHOICES = new HashMap<>();

    /** The gold of a "!", and the paler gold of a "?". */
    private static final int OFFER = 0xFFFFC83D, WAITING = 0xFFFFF0A0, PANEL = 0x90101018;

    public static void marks(String marks) {
        MARKS.clear();
        if (marks == null || marks.isEmpty()) return;
        for (String m : marks.split(";")) {
            int colon = m.indexOf(':');
            if (colon <= 0 || colon == m.length() - 1) continue;
            try {
                MARKS.put(Integer.parseInt(m.substring(0, colon)), m.charAt(colon + 1));
            } catch (NumberFormatException ignored) {
                // a mark from a newer server
            }
        }
    }

    public static void offer(int entityId, String choices) {
        if (choices == null || choices.isEmpty()) CHOICES.remove(entityId);
        else CHOICES.put(entityId, choices);
        if (CHOICES.size() > 64) CHOICES.keySet().removeIf(id -> id != entityId);
    }

    /** The quest buttons for this folk: "label|TOPIC|text|tip", one a line. */
    public static String choicesFor(int entityId) {
        return CHOICES.getOrDefault(entityId, "");
    }

    public static void journal(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (text.isEmpty()) {                     // the server's word to shut it (/village quests journal close)
            if (mc.screen instanceof QuestJournalScreen j) j.onClose();
            return;
        }
        if (mc.screen instanceof QuestJournalScreen j) j.update(text);
        else mc.setScreen(new QuestJournalScreen(text));
    }

    /** The mark over this folk, or 0. */
    public static char mark(int entityId) {
        Character c = MARKS.get(entityId);
        return c == null ? 0 : c;
    }

    /**
     * [FolkRenderer] The mark over a folk's head, a little above where its name would be, bobbing: a "!" or a "?" on a
     * dark tag, lit whatever the hour. Not while it is saying something (its bubble is there), and not from far off.
     */
    public static void draw(VillageFolkEntity folk, PoseStack pose, MultiBufferSource buffer, Font font, EntityRenderDispatcher dispatcher,
                            float partialTick) {
        char c = mark(folk.getId());
        if (c == 0 || folk.isInvisible()) return;
        if (dispatcher.distanceToSqr(folk) > 48.0 * 48.0) return;
        if (FolkTalkClient.saying(folk.getId(), folk.level().getGameTime()) != null) return;
        float bob = Mth.sin((folk.tickCount + partialTick) * 0.12F) * 0.06F;
        pose.pushPose();
        pose.translate(0.0F, folk.getBbHeight() + (folk.isBaby() ? 0.55F : 0.85F) + bob, 0.0F);
        pose.mulPose(dispatcher.cameraOrientation());
        pose.scale(0.05F, -0.05F, 0.05F);
        org.joml.Matrix4f matrix = pose.last().pose();
        String s = c == '!' ? "!" : c == '?' ? "?" : String.valueOf(c);     // the two marks there are, without a new string a frame
        float x = -font.width(s) / 2.0F;
        font.drawInBatch(s, x, 0.0F, 0x30FFFFFF, false, matrix, buffer, Font.DisplayMode.SEE_THROUGH, PANEL, LightTexture.FULL_BRIGHT);
        font.drawInBatch(s, x, 0.0F, c == '!' ? OFFER : WAITING, false, matrix, buffer, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        pose.popPose();
    }
}
