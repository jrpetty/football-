package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.DraughtsBoardBlock;
import com.jrpetty.mcassistant.block.DraughtsBoardBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/**
 * [leisure] The pieces on a draughts board, where the game has them (DraughtsBoardBlockEntity): round red and black men
 * on the board's green squares, a king two men high with a gold crown on it, the piece just moved standing a touch
 * proud for a moment. The board is drawn the same whichever way it faces; red's rows run toward the side it faces.
 */
public class DraughtsBoardRenderer implements BlockEntityRenderer<DraughtsBoardBlockEntity> {

    private static final ResourceLocation PIECES = ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "textures/entity/draughts_pieces.png");
    /** The board's top, in blocks; a man's height; a square. */
    private static final float TOP = 2.0F / 16.0F, MAN = 0.75F / 16.0F, SQ = 1.0F / 8.0F;

    public DraughtsBoardRenderer(BlockEntityRendererProvider.Context ctx) {
    }

    @Override
    public void render(DraughtsBoardBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (!(be.getBlockState().getBlock() instanceof DraughtsBoardBlock)) return;
        Direction red = be.getBlockState().getValue(DraughtsBoardBlock.FACING);
        byte[] cells = be.cells();
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutout(PIECES));
        PoseStack.Pose p = pose.last();
        for (int i = 0; i < 32 && i < cells.length; i++) {
            int piece = cells[i];
            if (piece == DraughtsBoardBlockEntity.EMPTY) continue;
            int r = i / 4, c = 2 * (i % 4) + (r + 1) % 2;
            int[] xz = square(red, r, c);
            float cx = (xz[0] + 0.5F) * SQ, cz = (xz[1] + 0.5F) * SQ;
            boolean redPiece = piece == DraughtsBoardBlockEntity.RED || piece == DraughtsBoardBlockEntity.RED_KING;
            boolean king = piece >= DraughtsBoardBlockEntity.RED_KING;
            float lift = i == be.lastTo() ? 0.25F / 16.0F : 0.0F;
            float u = redPiece ? 0.0F : 8.0F;
            float y0 = TOP + lift;
            man(vc, p, cx, y0, cz, u, light, overlay);
            if (king) {
                man(vc, p, cx, y0 + MAN, cz, u, light, overlay);
                float s = 0.3F / 16.0F;
                box(vc, p, cx - s, y0 + 2 * MAN, cz - s, cx + s, y0 + 2 * MAN + 0.35F / 16.0F, cz + s, 0.0F, 8.0F, 4.0F, 12.0F, 0.0F, 8.0F, 4.0F, 12.0F,
                    light, overlay);
            }
        }
    }

    /**
     * Where a square of the game (row from black's side, column) lies on the board, as {across, along} in eighths: red's
     * rows toward the side it faces, and always on the board's dark (green) squares, whichever way it faces.
     */
    static int[] square(Direction red, int r, int c) {
        return switch (red) {
            case NORTH -> new int[]{ 7 - c, 7 - r };
            case EAST -> new int[]{ r, c };
            case WEST -> new int[]{ 7 - r, 7 - c };
            default -> new int[]{ c, r };
        };
    }

    /** A man: a round piece, two boxes crossed (so it has no square corners), red or black, its top a shade lighter. */
    private static void man(VertexConsumer vc, PoseStack.Pose p, float cx, float y, float cz, float u, int light, int overlay) {
        float a = 0.78F / 16.0F, b = 0.5F / 16.0F;
        box(vc, p, cx - a, y, cz - b, cx + a, y + MAN, cz + b, u, 0.0F, u + 4.0F, 4.0F, u, 4.0F, u + 4.0F, 6.0F, light, overlay);
        box(vc, p, cx - b, y, cz - a, cx + b, y + MAN, cz + a, u, 0.0F, u + 4.0F, 4.0F, u, 4.0F, u + 4.0F, 6.0F, light, overlay);
    }

    /** A box: its top (and bottom) with one part of the texture, its sides with another (texture pixels of sixteen). */
    private static void box(VertexConsumer vc, PoseStack.Pose p, float x0, float y0, float z0, float x1, float y1, float z1,
                            float tu0, float tv0, float tu1, float tv1, float su0, float sv0, float su1, float sv1, int light, int overlay) {
        float a = tu0 / 16.0F, b = tv0 / 16.0F, c = tu1 / 16.0F, d = tv1 / 16.0F;
        float e = su0 / 16.0F, f = sv0 / 16.0F, g = su1 / 16.0F, h = sv1 / 16.0F;
        // top
        v(vc, p, x0, y1, z0, a, b, 0, 1, 0, light, overlay);
        v(vc, p, x0, y1, z1, a, d, 0, 1, 0, light, overlay);
        v(vc, p, x1, y1, z1, c, d, 0, 1, 0, light, overlay);
        v(vc, p, x1, y1, z0, c, b, 0, 1, 0, light, overlay);
        // north
        v(vc, p, x0, y0, z0, e, h, 0, 0, -1, light, overlay);
        v(vc, p, x0, y1, z0, e, f, 0, 0, -1, light, overlay);
        v(vc, p, x1, y1, z0, g, f, 0, 0, -1, light, overlay);
        v(vc, p, x1, y0, z0, g, h, 0, 0, -1, light, overlay);
        // south
        v(vc, p, x1, y0, z1, e, h, 0, 0, 1, light, overlay);
        v(vc, p, x1, y1, z1, e, f, 0, 0, 1, light, overlay);
        v(vc, p, x0, y1, z1, g, f, 0, 0, 1, light, overlay);
        v(vc, p, x0, y0, z1, g, h, 0, 0, 1, light, overlay);
        // west
        v(vc, p, x0, y0, z1, e, h, -1, 0, 0, light, overlay);
        v(vc, p, x0, y1, z1, e, f, -1, 0, 0, light, overlay);
        v(vc, p, x0, y1, z0, g, f, -1, 0, 0, light, overlay);
        v(vc, p, x0, y0, z0, g, h, -1, 0, 0, light, overlay);
        // east
        v(vc, p, x1, y0, z0, e, h, 1, 0, 0, light, overlay);
        v(vc, p, x1, y1, z0, e, f, 1, 0, 0, light, overlay);
        v(vc, p, x1, y1, z1, g, f, 1, 0, 0, light, overlay);
        v(vc, p, x1, y0, z1, g, h, 1, 0, 0, light, overlay);
    }

    private static void v(VertexConsumer vc, PoseStack.Pose p, float x, float y, float z, float u, float w, float nx, float ny, float nz,
                          int light, int overlay) {
        vc.addVertex(p, x, y, z).setColor(255, 255, 255, 255).setUv(u, w).setOverlay(overlay).setLight(light).setNormal(p, nx, ny, nz);
    }
}
