package com.jrpetty.mcassistant.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [player15] A picture of a building, made from its drawing's own blocks (the server sends them in the town's style:
 * entity/BuildDesk.picture) and drawn in three dimensions with the game's block renderer, turning slowly; drag it to turn
 * it yourself. Only the blocks that show (a face to the air) are drawn, and each drawing's are worked out once. Every
 * drawing the town has works, in every style, since the picture is the drawing. Public, so other pages (the property
 * market's) can show one too.
 */
public final class BlueprintPicture {

    private BlueprintPicture() {}

    private record Shown(List<BlockPos> at, List<BlockState> states, float cx, float cy, float cz, float span) {}

    private static final Map<String, Shown> CACHE = new HashMap<>();
    private static final Map<String, Float> YAW = new HashMap<>();
    private static long lastDrag;

    private static Shown shown(String key, int[] cells) {
        String k = key + "/" + java.util.Arrays.hashCode(cells);
        Shown s = CACHE.get(k);
        if (s != null) return s;
        if (CACHE.size() > 48) CACHE.clear();
        Set<Long> full = new HashSet<>();
        for (int i = 0; i + 3 < cells.length; i += 4) {
            BlockState st = Block.stateById(cells[i + 3]);
            if (st.isSolidRender(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) full.add(BlockPos.asLong(cells[i], cells[i + 1], cells[i + 2]));
        }
        List<BlockPos> at = new ArrayList<>();
        List<BlockState> states = new ArrayList<>();
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (int i = 0; i + 3 < cells.length; i += 4) {
            int x = cells[i], y = cells[i + 1], z = cells[i + 2];
            x0 = Math.min(x0, x); y0 = Math.min(y0, y); z0 = Math.min(z0, z);
            x1 = Math.max(x1, x); y1 = Math.max(y1, y); z1 = Math.max(z1, z);
            boolean hidden = full.contains(BlockPos.asLong(x + 1, y, z)) && full.contains(BlockPos.asLong(x - 1, y, z))
                && full.contains(BlockPos.asLong(x, y + 1, z)) && full.contains(BlockPos.asLong(x, y - 1, z))
                && full.contains(BlockPos.asLong(x, y, z + 1)) && full.contains(BlockPos.asLong(x, y, z - 1));
            if (hidden) continue;
            at.add(new BlockPos(x, y, z));
            states.add(Block.stateById(cells[i + 3]));
        }
        if (at.isEmpty()) { x0 = y0 = z0 = 0; x1 = y1 = z1 = 1; }
        float span = Math.max(x1 - x0 + 1, Math.max(y1 - y0 + 1, z1 - z0 + 1));
        s = new Shown(at, states, (x0 + x1 + 1) / 2f, (y0 + y1 + 1) / 2f, (z0 + z1 + 1) / 2f, span);
        CACHE.put(k, s);
        return s;
    }

    /** Draw it in this box. */
    public static void draw(GuiGraphics g, String key, int[] cells, int x, int y, int w, int h) {
        Shown s = shown(key, cells);
        float yaw = YAW.getOrDefault(key, 30f);
        if (System.currentTimeMillis() - lastDrag > 3000) {
            yaw += 0.15f;
            YAW.put(key, yaw);
        }
        float scale = Math.min(w, h) / (s.span() * 1.4f);
        PoseStack ps = g.pose();
        ps.pushPose();
        ps.translate(x + w / 2f, y + h / 2f + 4, 200);
        ps.scale(scale, -scale, scale);
        ps.mulPose(Axis.XP.rotationDegrees(25));
        ps.mulPose(Axis.YP.rotationDegrees(yaw));
        ps.translate(-s.cx(), -s.cy(), -s.cz());
        BlockRenderDispatcher br = Minecraft.getInstance().getBlockRenderer();
        MultiBufferSource.BufferSource buf = g.bufferSource();
        Lighting.setupForEntityInInventory();
        for (int i = 0; i < s.at().size(); i++) {
            BlockPos p = s.at().get(i);
            ps.pushPose();
            ps.translate(p.getX(), p.getY(), p.getZ());
            try {
                br.renderSingleBlock(s.states().get(i), ps, buf, 0xF000F0, OverlayTexture.NO_OVERLAY);
            } catch (RuntimeException e) {
                // a block that will not draw out of the world is left out of the picture
            }
            ps.popPose();
        }
        buf.endBatch();
        ps.popPose();
        Lighting.setupFor3DItems();
    }

    /** Turned by a drag of the mouse. */
    public static void drag(String key, double dx) {
        YAW.put(key, YAW.getOrDefault(key, 30f) + (float) dx * 1.5f);
        lastDrag = System.currentTimeMillis();
    }
}
