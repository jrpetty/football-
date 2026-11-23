package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.pa.PlayerAssistantEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * [pa33c-2b] What an assistant says, drawn on the screen over its head rather than in the world, so the whole bubble is
 * always readable: kept inside the screen on every edge, and when it is right up close, beside its head instead of
 * above it (where the top of the screen would cut it). Where its head is on screen is worked out each frame from the
 * world camera; the bubble is drawn with the HUD.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class PaBubbles {

    private PaBubbles() {}

    /** Where a speaking assistant's head is on screen (GUI pixels), how far away it is, and what it says. */
    record Spot(String name, String text, float x, float y, double dist) {}

    private static final List<Spot> SPOTS = new ArrayList<>();

    @SubscribeEvent
    public static void onStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        SPOTS.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        Vec3 cam = event.getCamera().getPosition();
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Matrix4f mvp = new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix());
        if (event.getPoseStack() != null) mvp.mul(event.getPoseStack().last().pose());
        int gw = mc.getWindow().getGuiScaledWidth(), gh = mc.getWindow().getGuiScaledHeight();
        long now = mc.level.getGameTime();
        for (PlayerAssistantEntity a : mc.level.getEntitiesOfClass(PlayerAssistantEntity.class, mc.player.getBoundingBox().inflate(32))) {
            String said = PaClient.bubble(a.getId(), now);
            if (said == null) continue;
            Vec3 head = a.getPosition(pt).add(0, a.getBbHeight() + 0.25, 0);
            Vector4f v = new Vector4f((float) (head.x - cam.x), (float) (head.y - cam.y), (float) (head.z - cam.z), 1.0F);
            mvp.transform(v);
            if (v.w <= 0.05F) continue;                                   // behind the camera
            float sx = (v.x / v.w + 1) / 2 * gw, sy = (1 - v.y / v.w) / 2 * gh;
            SPOTS.add(new Spot(a.shownName(), said, sx, sy, head.distanceTo(cam)));
        }
    }

    /** Draw the bubbles (with the HUD), nearest last so it is on top. */
    static void draw(GuiGraphics g, Minecraft mc) {
        if (SPOTS.isEmpty()) return;
        Font font = mc.font;
        int w = g.guiWidth(), h = g.guiHeight();
        List<Spot> order = new ArrayList<>(SPOTS);
        order.sort((p, q) -> Double.compare(q.dist(), p.dist()));
        for (Spot s : order) {
            int wrap = Math.min(190, w - 16);
            List<FormattedCharSequence> lines = font.split(net.minecraft.network.chat.Component.literal(s.text()), wrap);
            if (lines.size() > 5) lines = lines.subList(0, 5);
            int widest = font.width(s.name());
            for (FormattedCharSequence l : lines) widest = Math.max(widest, font.width(l));
            int[] box = com.jrpetty.mcassistant.pa.PaLayout.bubble(s.x(), s.y(), widest + 10, 13 + lines.size() * 10 + 3, s.dist(), w, h);
            int x = box[0], y = box[1], bw = box[2], bh = box[3];
            g.fill(x, y, x + bw, y + bh, 0xD0122A2E);
            g.fill(x, y, x + 2, y + bh, 0xFF2BB5A0);
            g.drawString(font, s.name(), x + (bw - font.width(s.name())) / 2, y + 3, 0xFF8FE3D3, false);
            for (int i = 0; i < lines.size(); i++)
                g.drawString(font, lines.get(i), x + (bw - font.width(lines.get(i))) / 2, y + 14 + i * 10, 0xFFF2F7F6, false);
        }
    }
}
