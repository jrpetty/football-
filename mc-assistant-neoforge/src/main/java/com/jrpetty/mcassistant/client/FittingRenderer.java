package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.block.FittingBlock;
import com.jrpetty.mcassistant.block.FittingBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

import java.util.List;

/**
 * [till] What's on a fitting, drawn on it: the wares on a shelf's two shelves, under a case's glass, heaped in a crate,
 * hanging on a rack; and a price board's chalked prices. The real stacks in its slots (synced from the server), so what
 * you see is what's for sale.
 */
public final class FittingRenderer implements BlockEntityRenderer<FittingBlockEntity> {

    private final ItemRenderer items;
    private final Font font;

    public FittingRenderer(BlockEntityRendererProvider.Context ctx) {
        this.items = ctx.getItemRenderer();
        this.font = ctx.getFont();
    }

    @Override
    public void render(FittingBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        FittingBlock.Kind kind = be.kind();
        if (kind == FittingBlock.Kind.LARDER || kind == FittingBlock.Kind.BASKETS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.distanceToSqr(be.getBlockPos().getCenter()) > 32 * 32) return;
        Direction face = be.getBlockState().getValue(FittingBlock.FACING);
        pose.pushPose();
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-face.toYRot() + 180));   // north-facing model space: the front at -z
        pose.translate(-0.5, 0, -0.5);
        if (kind == FittingBlock.Kind.BOARD) board(be, pose, buffers);
        else wares(be, kind, pose, buffers, light, overlay);
        pose.popPose();
    }

    private void wares(FittingBlockEntity be, FittingBlock.Kind kind, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        List<ItemStack> shown = be.shown();
        for (int i = 0; i < shown.size(); i++) {
            ItemStack s = shown.get(i);
            if (s.isEmpty()) continue;
            pose.pushPose();
            boolean block = s.getItem() instanceof BlockItem;
            switch (kind) {
                case SHELF -> {
                    if (i < 3) pose.translate(0.78 - i * 0.28, 2.0 / 16 + (block ? 0.12 : 0.17), 0.42);
                    else pose.translate(i == 3 ? 0.68 : 0.32, 8.5 / 16 + (block ? 0.12 : 0.17), 0.45);
                    float sc = block ? 0.32F : 0.36F;
                    pose.scale(sc, sc, sc);
                }
                case CASE -> {
                    if (i >= 3) { pose.popPose(); continue; }
                    pose.translate(0.76 - i * 0.26, 6.3 / 16 + 0.01, 0.5);
                    pose.mulPose(Axis.XP.rotationDegrees(90));
                    pose.scale(0.3F, 0.3F, 0.3F);
                }
                case CRATE -> {
                    if (i >= 4) { pose.popPose(); continue; }
                    double x = i % 2 == 0 ? 0.33 : 0.67, z = i < 2 ? 0.36 : 0.66;
                    pose.translate(x, 5.6 / 16 + 0.02 + i * 0.006, z);
                    pose.mulPose(Axis.XP.rotationDegrees(90));
                    pose.mulPose(Axis.ZP.rotationDegrees(i * 37));
                    pose.scale(0.42F, 0.42F, 0.42F);
                }
                case RACK -> {
                    if (i >= 4) { pose.popPose(); continue; }
                    pose.translate(0.2 + i * 0.2, 0.62, 0.5);
                    pose.mulPose(Axis.YP.rotationDegrees(90));
                    pose.scale(0.45F, 0.45F, 0.45F);
                }
                default -> { }
            }
            items.renderStatic(s, ItemDisplayContext.FIXED, light, overlay, pose, buffers, be.getLevel(), (int) be.getBlockPos().asLong() + i);
            pose.popPose();
        }
    }

    private void board(FittingBlockEntity be, PoseStack pose, MultiBufferSource buffers) {
        List<String> lines = be.board();
        if (lines.isEmpty()) return;
        pose.pushPose();
        pose.translate(0.5, 14.6 / 16, 7.1 / 16);
        float s = 0.0085F;
        pose.scale(-s, -s, s);
        pose.mulPose(Axis.YP.rotationDegrees(180));
        Matrix4f m = pose.last().pose();
        int width = 140;                                                     // the board, 12 pixels of 16, in these units
        String head = lines.get(0);
        if (font.width(head) > width) head = font.plainSubstrByWidth(head, width - 4) + ".";
        font.drawInBatch(head, -font.width(head) / 2F, 0, 0xFFF4E3A0, false, m, buffers, Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split("\\|", 2);
            int y = 4 + i * 10;
            font.drawInBatch(parts[0], -width / 2F + 4, y, 0xFFEDEDED, false, m, buffers, Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
            if (parts.length > 1) font.drawInBatch(parts[1], width / 2F - 4 - font.width(parts[1]), y, 0xFFB8E68A, false, m, buffers, Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
        }
        pose.popPose();
    }
}
