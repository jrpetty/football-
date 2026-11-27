package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

import javax.annotation.Nullable;

/**
 * [wpc] The building a player is choosing, shown standing where it would stand (entity/BuildPicker): every block of its
 * drawing drawn translucent in the world, turned the way it faces, tinted red where something already stands in its way,
 * and its lot outlined on the ground. "Walk round it" closes the screen and keeps it up (a line at the top of the screen
 * says so, and U brings the builder back); it goes when the order is placed, the builder is left, or after ten minutes.
 * On the progress step the same picture shows only what is still to be built.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT)
public final class BuildGhost {

    private BuildGhost() {}

    @Nullable private static CompoundTag ghost;
    private static long until;
    private static int[] cells = new int[0];

    /** The server's word: a picture to show, or "off". */
    static void set(CompoundTag g) {
        if (g.getBoolean("off")) {
            ghost = null;
            cells = new int[0];
            return;
        }
        ghost = g;
        cells = g.getIntArray("cells");
        until = System.currentTimeMillis() + (g.getBoolean("walk") ? 600_000 : 180_000);
    }

    /** Walking round it with the screen closed (U goes back to the builder). */
    public static boolean walking() {
        CompoundTag g = ghost;
        return g != null && g.getBoolean("walk") && System.currentTimeMillis() < until;
    }

    @SubscribeEvent
    public static void onStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        CompoundTag g = ghost;                                              // drawn under F1 too: it is what a film of it is for
        if (g == null || mc.level == null) return;
        if (System.currentTimeMillis() > until) { ghost = null; return; }
        Vec3 cam = event.getCamera().getPosition();
        PoseStack ps = event.getPoseStack();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        // the blocks, translucent: the house as it would stand
        BlockRenderDispatcher br = mc.getBlockRenderer();
        float pulse = 0.6f + 0.08f * (float) Math.sin(System.currentTimeMillis() / 300.0);
        boolean rest = g.getBoolean("rest");
        VertexConsumer base = buf.getBuffer(RenderType.translucent());
        int[] c = cells;
        for (int i = 0; i + 4 < c.length; i += 5) {
            BlockState st = Block.stateById(c[i + 3]);
            if (st.isAir() || st.getRenderShape() != RenderShape.MODEL) continue;
            boolean bad = c[i + 4] == 1;
            float r = bad ? 1f : rest ? 0.75f : 1f, gr = bad ? 0.25f : 1f, b = bad ? 0.25f : rest ? 0.85f : 1f;
            VertexConsumer vc = new Tint(base, r, gr, b, bad ? 0.75f : rest ? 0.45f : pulse);
            BakedModel model = br.getBlockModel(st);
            ps.pushPose();
            ps.translate(c[i], c[i + 1], c[i + 2]);
            ps.translate(0.5, 0.5, 0.5);
            ps.scale(1.002f, 1.002f, 1.002f);                                 // a hair proud of the real blocks, never flickering into them
            ps.translate(-0.5, -0.5, -0.5);
            br.getModelRenderer().renderModel(ps.last(), vc, st, model, 1f, 1f, 1f, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                ModelData.EMPTY, RenderType.translucent());
            ps.popPose();
        }
        buf.endBatch(RenderType.translucent());
        // the lot, outlined on the ground
        int[] lot = g.getIntArray("lot");
        if (lot.length >= 5) {
            VertexConsumer lines = buf.getBuffer(RenderType.lines());
            boolean ok = g.getBoolean("ok");
            float lr = ok ? 0.25f : 1f, lg = ok ? 0.9f : 0.3f;
            AABB box = new AABB(lot[0], lot[4], lot[1], lot[2] + 1, lot[4] + 0.06, lot[3] + 1);
            LevelRenderer.renderLineBox(ps, lines, box.inflate(0.02), lr, lg, 0.3f, 1f);
            // corner posts, so the lot reads from across the street
            int[][] corners = { { lot[0], lot[1] }, { lot[2], lot[1] }, { lot[0], lot[3] }, { lot[2], lot[3] } };
            for (int[] k : corners) {
                LevelRenderer.renderLineBox(ps, lines, new AABB(k[0] + 0.3, lot[4], k[1] + 0.3, k[0] + 0.7, lot[4] + 2.5, k[1] + 0.7), 1f, 0.85f, 0.2f, 1f);
            }
            buf.endBatch(RenderType.lines());
        }
        ps.popPose();
    }

    /** While walking round it: a line at the top of the screen, so it is plain how to get back. */
    @SubscribeEvent
    public static void onGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!walking() || mc.screen != null || mc.options.hideGui) return;
        String line = ghost.getString("say").isEmpty() ? "Walking round your building. Press U to go back to the builder." : ghost.getString("say");
        var g = event.getGuiGraphics();
        int w = mc.font.width(line) + 12, x = (g.guiWidth() - w) / 2, y = 6;
        UiStyle.panel(g, x, y, w, 16);
        g.drawString(mc.font, Component.literal(line), x + 6, y + 4, UiStyle.TEXT, false);
    }

    /** A vertex consumer that tints and fades everything drawn through it. */
    private record Tint(VertexConsumer d, float r, float g, float b, float a) implements VertexConsumer {
        @Override public VertexConsumer addVertex(float x, float y, float z) { d.addVertex(x, y, z); return this; }
        @Override public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            d.setColor((int) (red * r), (int) (green * g), (int) (blue * b), (int) (alpha * a));
            return this;
        }
        @Override public VertexConsumer setUv(float u, float v) { d.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { d.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { d.setUv2(u, v); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { d.setNormal(x, y, z); return this; }
    }
}
