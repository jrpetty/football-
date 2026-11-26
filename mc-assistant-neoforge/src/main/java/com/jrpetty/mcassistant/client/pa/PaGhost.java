package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.pa.PaBlueprint;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * [featpa] The ghost of a blueprint where its player pointed: every block of the copy drawn see-through in place, turned
 * and mirrored as set on the Blueprints screen, the player's own blocks in its way outlined red, the whole box outlined.
 * Blocks already right in the world are left out, so while it builds the ghost fills in with the real thing, block by
 * block: the build's progress, on the ground itself. Hidden with the HUD (F1) for a clean shot.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT)
public final class PaGhost {

    private PaGhost() {}

    @Nullable static PaBlueprint blueprint;
    @Nullable private static BlockPos anchor;
    private static int rot;
    private static boolean mirror;
    private static final Set<Long> clash = new HashSet<>();
    private static final List<PaBlueprint.Spot> spots = new ArrayList<>();
    private static long shownAt;

    static void set(BlockPos at, int r, boolean m, long[] clashes) {
        anchor = at;
        rot = r;
        mirror = m;
        clash.clear();
        for (long c : clashes) clash.add(c);
        spots.clear();
        if (blueprint != null) for (PaBlueprint.Spot s : blueprint.spots(at, m, r)) if (!s.state().isAir()) spots.add(s);
        shownAt = System.currentTimeMillis();
    }

    static void off() {
        anchor = null;
        spots.clear();
        clash.clear();
    }

    static boolean on() {
        return anchor != null && blueprint != null;
    }

    /** A vertex stream that fades what passes through it (a block model drawn see-through, tinted). */
    private record Fade(VertexConsumer in, float alpha, float r, float g, float b) implements VertexConsumer {
        @Override public VertexConsumer addVertex(float x, float y, float z) { in.addVertex(x, y, z); return this; }
        @Override public VertexConsumer setColor(int cr, int cg, int cb, int ca) { in.setColor((int) (cr * r), (int) (cg * g), (int) (cb * b), (int) (ca * alpha)); return this; }
        @Override public VertexConsumer setUv(float u, float v) { in.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { in.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { in.setUv2(u, v); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { in.setNormal(x, y, z); return this; }
    }

    @SubscribeEvent
    public static void onStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || !on() || mc.level == null) return;
        PaBlueprint bp = blueprint;
        BlockPos at = anchor;
        if (bp == null || at == null) return;
        Vec3 cam = event.getCamera().getPosition();
        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        float pulse = 0.55f + 0.25f * (float) Math.sin(System.currentTimeMillis() / 300.0);
        // the blocks still to place, see-through (the nearest few thousand as blocks, the rest as outlines)
        VertexConsumer tl = new Fade(buf.getBuffer(RenderType.translucent()), 0.45f, 0.75f, 0.95f, 1.0f);
        var models = mc.getBlockRenderer();
        int drawn = 0;
        List<PaBlueprint.Spot> outline = new ArrayList<>();
        for (PaBlueprint.Spot s : spots) {
            BlockState now = mc.level.getBlockState(s.pos());
            if (now == s.state() || now.getBlock() == s.state().getBlock() && sameEnough(now, s.state())) continue;   // built: the real thing shows
            if (clash.contains(s.pos().asLong())) continue;
            double d2 = s.pos().distToCenterSqr(cam);
            if (s.state().getRenderShape() == RenderShape.MODEL && drawn < 3000 && d2 < 64 * 64) {
                ps.pushPose();
                ps.translate(s.pos().getX(), s.pos().getY(), s.pos().getZ());
                models.getModelRenderer().renderModel(ps.last(), tl, s.state(), models.getBlockModel(s.state()), 1f, 1f, 1f,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, net.neoforged.neoforge.client.model.data.ModelData.EMPTY, RenderType.translucent());
                ps.popPose();
                drawn++;
            } else if (outline.size() < 4000) outline.add(s);
        }
        buf.endBatch(RenderType.translucent());
        VertexConsumer lines = buf.getBuffer(RenderType.lines());
        for (PaBlueprint.Spot s : outline) LevelRenderer.renderLineBox(ps, lines, new AABB(s.pos()).deflate(0.08), 0.55f, 0.85f, 1f, 0.7f);
        // the player's own blocks in the way: red
        for (long c : clash) LevelRenderer.renderLineBox(ps, lines, new AABB(BlockPos.of(c)).inflate(0.01), 1f, 0.25f, 0.2f, 0.9f);
        // the whole box
        int[] f = PaBlueprint.size(bp.sx, bp.sz, rot);
        LevelRenderer.renderLineBox(ps, lines, new AABB(at.getX(), at.getY(), at.getZ(), at.getX() + f[0], at.getY() + bp.sy, at.getZ() + f[1]), 0.3f, 0.9f, 0.85f, pulse);
        buf.endBatch(RenderType.lines());
        ps.popPose();
    }

    /** Built near enough for the ghost to stand aside (the server keeps the exact count). */
    private static boolean sameEnough(BlockState now, BlockState want) {
        for (var p : want.getProperties()) {
            String n = p.getName();
            if (n.equals("waterlogged") || n.equals("north") || n.equals("south") || n.equals("east") || n.equals("west") || n.equals("up")
                || n.equals("distance") || n.equals("persistent") || n.equals("powered") || n.equals("occupied") || n.equals("lit")) continue;
            if (!now.hasProperty(p) || !now.getValue(p).equals(want.getValue(p))) return false;
        }
        return true;
    }
}
