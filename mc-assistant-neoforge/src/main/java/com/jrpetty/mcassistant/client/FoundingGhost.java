package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * [found23] Where the town would go, outlined on the real ground while it is being chosen (client/FoundingScreen): the
 * square (gold), its first ring of lots and street (green, red where it may not be founded) and the ground to be made
 * level (white), each a line that follows the lie of the land, with a post at the heart. Shown while the screen is open
 * or put away to look, and for two minutes after the founding is confirmed.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT)
public final class FoundingGhost {

    private FoundingGhost() {}

    private static int x, y, z, level, ring, square;
    private static boolean ok, on;
    private static long until;

    static void set(int cx, int cy, int cz, int levelled, int bare, int plaza, boolean good) {
        x = cx; y = cy; z = cz; level = levelled; ring = bare; square = plaza; ok = good;
        on = true;
        until = Long.MAX_VALUE;
    }

    /** Kept for two minutes more (the founding confirmed: there it is). */
    static void keep() {
        until = System.currentTimeMillis() + 120_000;
    }

    /** Gone, unless kept. */
    static void fade() {
        if (until == Long.MAX_VALUE) on = false;
    }

    @SubscribeEvent
    public static void onStage(RenderLevelStageEvent event) {
        if (!on || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (Minecraft.getInstance().options.hideGui) return;                     // [vision27] F1, a clean camera shot: no outlines
        if (System.currentTimeMillis() > until) { on = false; return; }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel lv = mc.level;
        if (lv == null) return;
        Vec3 cam = event.getCamera().getPosition();
        if (Math.abs(cam.x - x) > 400 || Math.abs(cam.z - z) > 400) return;
        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buf.getBuffer(RenderType.lines());
        float pulse = 0.65F + 0.35F * (float) Math.sin(System.currentTimeMillis() / 300.0);
        square(ps, lines, lv, level, 1F, 1F, 1F, 0.8F);
        if (ok) square(ps, lines, lv, ring, 0.3F, 1F, 0.3F, pulse);
        else square(ps, lines, lv, ring, 1F, 0.25F, 0.2F, pulse);
        square(ps, lines, lv, square, 1F, 0.82F, 0.2F, 1F);
        int gy = ground(lv, x, z);
        LevelRenderer.renderLineBox(ps, lines, new AABB(x + 0.3, gy, z + 0.3, x + 0.7, gy + 32, z + 0.7), 1F, 0.82F, 0.2F, 1F);   // seen from afar
        buf.endBatch(RenderType.lines());
        ps.popPose();
    }

    /** A square r out from the heart, its edge laid on the ground two blocks at a time, with a post at each corner. */
    private static void square(PoseStack ps, VertexConsumer lines, ClientLevel lv, int r, float red, float green, float blue, float a) {
        var pose = ps.last();
        int step = r > 60 ? 4 : 2;
        int[][] sides = { { -r, -r, 1, 0 }, { r, -r, 0, 1 }, { r, r, -1, 0 }, { -r, r, 0, -1 } };
        for (int[] s : sides) {
            int px = x + s[0], pz = z + s[1];
            float py = ground(lv, px, pz) + 0.08F;
            LevelRenderer.renderLineBox(ps, lines, new AABB(px, py, pz, px + 1, py + 3, pz + 1), red, green, blue, a);
            for (int d = 0; d < 2 * r; d += step) {
                int nx = px + s[2] * step, nz = pz + s[3] * step;
                float ny = ground(lv, nx, nz) + 0.08F;
                float ax = px + 0.5F, az = pz + 0.5F, bx = nx + 0.5F, bz = nz + 0.5F;
                float dx = bx - ax, dy = ny - py, dz = bz - az;
                float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len > 0) {
                    lines.addVertex(pose, ax, py, az).setColor(red, green, blue, a).setNormal(pose, dx / len, dy / len, dz / len);
                    lines.addVertex(pose, bx, ny, bz).setColor(red, green, blue, a).setNormal(pose, dx / len, dy / len, dz / len);
                }
                px = nx; pz = nz; py = ny;
            }
        }
    }

    private static int ground(ClientLevel lv, int gx, int gz) {
        if (!lv.hasChunk(gx >> 4, gz >> 4)) return y;
        return lv.getHeight(Heightmap.Types.MOTION_BLOCKING, gx, gz);
    }
}
