package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import javax.annotation.Nullable;

/**
 * [player15] Where a building would go, outlined on the ground itself while its plot is being chosen (entity/StartBusiness):
 * its footprint and height as a box of lines, green where it may go and red where it may not, with a mark at its front.
 * Gone when the plot is confirmed, the page moves on, or after two minutes.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT)
public final class LifeGhost {

    private LifeGhost() {}

    @Nullable private static CompoundTag ghost;
    private static long until;

    static void set(CompoundTag g) {
        if (g.getBoolean("off")) {
            ghost = null;
            return;
        }
        ghost = g;
        until = System.currentTimeMillis() + 120_000;
    }

    @SubscribeEvent
    public static void onStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        CompoundTag g = ghost;
        if (g == null) return;
        if (System.currentTimeMillis() > until) { ghost = null; return; }
        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = event.getCamera().getPosition();
        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buf.getBuffer(RenderType.lines());
        int x = g.getInt("x"), y = g.getInt("y"), z = g.getInt("z"), gx = g.getInt("gx"), gz = g.getInt("gz"), h = Math.max(3, g.getInt("h"));
        boolean ok = g.getBoolean("ok");
        float r = ok ? 0.2f : 1f, gr = ok ? 1f : 0.25f, b = 0.25f;
        float pulse = 0.6f + 0.4f * (float) Math.sin(System.currentTimeMillis() / 250.0);
        AABB box = new AABB(x - gx, y, z - gz, x + gx + 1, y + h, z + gz + 1);
        LevelRenderer.renderLineBox(ps, lines, box, r, gr, b, pulse);
        LevelRenderer.renderLineBox(ps, lines, new AABB(x - gx, y, z - gz, x + gx + 1, y + 0.05, z + gz + 1).inflate(0.02), r, gr, b, 1f);
        // the front: a small marker before the middle of its front wall
        net.minecraft.core.Direction f = net.minecraft.core.Direction.from2DDataValue(g.getInt("face"));
        double fx = x + 0.5 + f.getStepX() * (gx + 1.5), fz = z + 0.5 + f.getStepZ() * (gz + 1.5);
        if (f.getAxis() == net.minecraft.core.Direction.Axis.X) fx = x + 0.5 + f.getStepX() * (gx + 1.5);
        LevelRenderer.renderLineBox(ps, lines, new AABB(fx - 0.3, y, fz - 0.3, fx + 0.3, y + 1.2, fz + 0.3), 1f, 0.85f, 0.2f, 1f);
        buf.endBatch(RenderType.lines());
        ps.popPose();
    }
}
