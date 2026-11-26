package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

import static com.jrpetty.mcassistant.client.UiStyle.*;

/**
 * [wpb] Where the work is, on the player's screen (entity/WorkWay sends it): a compass at the top of the screen, an arrow
 * that turns as the player turns toward the workplace with its name and the distance, and under it the current task's
 * spot; a beam of light standing at each spot in the world (gold for the workplace, green for the task, white for a place
 * shown from a page), the field's outline on the ground, and a mine's head first while the player is above and away from
 * the shaft. Off with the arrow switched off on the jobs page (a "Show me" still shows). Nothing drawn with F1.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT)
public final class WorkWayClient {

    private WorkWayClient() {}

    @Nullable private static CompoundTag way;
    static final int GROUND = 0xD0101418, GOLD = 0xFFF2C14E, GREEN = 0xFF8EE07A, SOFT = 0xFFB9C2CC;

    public static void set(CompoundTag t) {
        way = t == null || t.isEmpty() ? null : t;
    }

    /** The spot the main arrow leads to now: the mine's head while above and away from it, else the workplace. */
    @Nullable
    static int[] leadTo(CompoundTag m, Minecraft mc, String[] label) {
        int[] at = m.getIntArray("at");
        if (at.length < 3) return null;
        label[0] = m.getString("l");
        int[] via = m.getIntArray("via");
        if (via.length == 3 && mc.player != null) {
            double above = mc.player.getY() - at[1];
            double toHead = Math.hypot(mc.player.getX() - via[0], mc.player.getZ() - via[2]);
            if (above > 6 && toHead > 5) {
                label[0] = m.getString("vl");
                return via;
            }
        }
        return at;
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        CompoundTag w = way;
        if (w == null || mc.player == null || mc.options.hideGui) return;
        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;
        boolean on = w.getBoolean("on");
        int y = 20;
        if (w.contains("show")) y = line(g, font, mc, w.getCompound("show"), y, 0xFFFFFFFF, true);
        if (on && w.contains("main")) y = line(g, font, mc, w.getCompound("main"), y, GOLD, true);
        if (on && w.contains("task")) line(g, font, mc, w.getCompound("task"), y, GREEN, false);
    }

    /** One line of the compass: the turning arrow, the name, the distance. Returns the y under it. */
    private static int line(GuiGraphics g, Font font, Minecraft mc, CompoundTag m, int y, int colour, boolean big) {
        String[] label = new String[1];
        int[] at = leadTo(m, mc, label);
        if (at == null) return y;
        double dx = at[0] + 0.5 - mc.player.getX(), dz = at[2] + 0.5 - mc.player.getZ();
        int dist = (int) Math.sqrt(dx * dx + dz * dz + Math.pow(at[1] - mc.player.getY(), 2));
        double to = Math.toDegrees(Math.atan2(-dx, dz));
        float rel = (float) Mth.wrapDegrees(to - mc.player.getYRot());
        String dy = at[1] - mc.player.getY() > 4 ? " ↑" : at[1] - mc.player.getY() < -4 ? " ↓" : "";
        String text = clip(font, label[0], big ? 240 : 200) + "   " + dist + " m" + dy;
        int h = big ? 18 : 14;
        int w = font.width(text) + 2 * 6 + h;
        int x = (g.guiWidth() - w) / 2;
        g.fill(x, y, x + w, y + h, GROUND);
        g.fill(x, y + h - 1, x + w, y + h, colour);
        int cx = x + 3 + h / 2 - 1, cy = y + h / 2;
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        g.pose().mulPose(Axis.ZP.rotationDegrees(rel));
        if (big) g.pose().scale(1.5F, 1.5F, 1);
        g.drawString(font, "▲", -font.width("▲") / 2, -4, dist < 4 ? 0xFF9BE07A : colour, false);
        g.pose().popPose();
        g.drawString(font, text, x + h + 4, y + (h - 8) / 2, big ? 0xFFFFFFFF : SOFT, false);
        return y + h + 2;
    }

    // ------------------------------------------------------------------ the beams

    @SubscribeEvent
    public static void onStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        CompoundTag w = way;
        if (w == null || mc.player == null || mc.options.hideGui) return;
        boolean on = w.getBoolean("on");
        Vec3 cam = event.getCamera().getPosition();
        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        float pulse = 0.55f + 0.25f * (float) Math.sin(System.currentTimeMillis() / 300.0);
        VertexConsumer glow = buf.getBuffer(RenderType.lightning());
        if (w.contains("show")) beam(ps, glow, w.getCompound("show").getIntArray("at"), 0.95f, 0.95f, 1f, pulse, 0.25f);
        if (on && w.contains("main")) {
            CompoundTag m = w.getCompound("main");
            String[] l = new String[1];
            int[] at = leadTo(m, mc, l);
            if (at != null) beam(ps, glow, at, 1f, 0.78f, 0.25f, pulse, 0.3f);
        }
        if (on && w.contains("task")) beam(ps, glow, w.getCompound("task").getIntArray("at"), 0.45f, 1f, 0.4f, pulse, 0.18f);
        buf.endBatch(RenderType.lightning());
        if (on && w.contains("main") && w.getCompound("main").contains("rect")) {
            int[] r = w.getCompound("main").getIntArray("rect");
            if (r.length == 5) {
                VertexConsumer lines = buf.getBuffer(RenderType.lines());
                LevelRenderer.renderLineBox(ps, lines, new AABB(r[0], r[4], r[1], r[2] + 1, r[4] + 0.15, r[3] + 1), 1f, 0.8f, 0.3f, 0.9f);
                buf.endBatch(RenderType.lines());
            }
        }
        ps.popPose();
    }

    /** A column of light: four faces, fading as it rises. */
    private static void beam(PoseStack ps, VertexConsumer vc, int[] at, float r, float g, float b, float a, float half) {
        if (at == null || at.length < 3) return;
        Matrix4f m = ps.last().pose();
        float x = at[0] + 0.5f, z = at[2] + 0.5f, y0 = at[1] + 1.0f, y1 = y0 + 48f;
        float[][] corners = { { -half, -half }, { half, -half }, { half, half }, { -half, half } };
        for (int i = 0; i < 4; i++) {
            float[] c0 = corners[i], c1 = corners[(i + 1) % 4];
            vc.addVertex(m, x + c0[0], y0, z + c0[1]).setColor(r, g, b, a);
            vc.addVertex(m, x + c1[0], y0, z + c1[1]).setColor(r, g, b, a);
            vc.addVertex(m, x + c1[0], y1, z + c1[1]).setColor(r, g, b, 0f);
            vc.addVertex(m, x + c0[0], y1, z + c0[1]).setColor(r, g, b, 0f);
        }
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        CompoundTag w = way;
        if (w == null || mc.level == null || mc.player == null || mc.level.getGameTime() % 10 != 0) return;
        if (w.getBoolean("on") && w.contains("task")) {
            int[] t = w.getCompound("task").getIntArray("at");
            if (t.length == 3) mc.level.addParticle(ParticleTypes.END_ROD, t[0] + 0.5, t[1] + 1.3, t[2] + 0.5, 0, 0.03, 0);
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        way = null;
    }
}
