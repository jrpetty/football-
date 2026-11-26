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
        if (kind == FittingBlock.Kind.BOARD) {
            board(be, pose, buffers, light, overlay, false);
            board(be, pose, buffers, light, overlay, true);                 // both faces chalked: it stands in the street
        }
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
                    float sc = block ? 0.36F : 0.44F;
                    pose.scale(sc, sc, sc);
                }
                case CASE -> {
                    if (i >= 3) { pose.popPose(); continue; }
                    pose.translate(0.76 - i * 0.26, 6.3 / 16 + (block ? 0.09 : 0.01), 0.5);
                    if (!block) pose.mulPose(Axis.XP.rotationDegrees(90));
                    pose.scale(block ? 0.22F : 0.34F, block ? 0.22F : 0.34F, block ? 0.22F : 0.34F);
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

    /** Rows of wares a board shows at once; more, and it turns its pages every five seconds. */
    static final int ROWS = 7;
    /** The slate in board units: 150 wide (twelve pixels of sixteen), its height, the padding. */
    private static final float S = 0.005F, W = 150F, H = 118F, PAD = 13F;

    /**
     * The board's chalk: the shop's name at the head (scaled down to fit), then its wares a page at a time, each with its
     * icon, its name to the left (cut with an ellipsis where it would run into the price) and its price to the right in a
     * column, all inside the slate's frame, flat on its face. Lit as the block is lit, never darker than a lantern's glow.
     */
    private void board(FittingBlockEntity be, PoseStack pose, MultiBufferSource buffers, int light, int overlay, boolean back) {
        List<String> lines = be.board();
        if (lines.isEmpty() || be.getLevel() == null) return;
        int lit = LightTexture.pack(Math.max(LightTexture.block(light), 7), LightTexture.sky(light));
        pose.pushPose();
        if (back) {
            pose.translate(0.5, 14.5 / 16.0, 8.6 / 16.0 + 0.0025);
        } else {
            pose.translate(0.5, 14.5 / 16.0, 7.4 / 16.0 - 0.0025);
            pose.mulPose(Axis.YP.rotationDegrees(180));                    // the slate's face is the model's north
        }
        // the head: the shop's name
        String head = lines.get(0);
        float room = W - 2 * PAD;
        float hw = font.width(head), hs = Math.min(1F, room / Math.max(1F, hw));
        pose.pushPose();
        pose.scale(S, -S, S);
        pose.translate(0, 5, 0);
        pose.scale(hs, hs, 1F);
        font.drawInBatch(head, -hw / 2F, 0, 0xFFF4E3A0, false, pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, lit);
        pose.popPose();
        // a chalk rule under it
        pose.pushPose();
        pose.scale(S, -S, S);
        String rule = "- - - - - - - - - - - - -";
        font.drawInBatch(rule, -font.width(rule) / 2F, 14, 0x80E8E8E8, false, pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, lit);
        pose.popPose();
        int wares = lines.size() - 1;
        if (wares <= 0) { pose.popPose(); return; }
        int pages = (wares + ROWS - 1) / ROWS;
        int page = (int) ((be.getLevel().getGameTime() / 100L) % pages);
        float left = -W / 2F + PAD, right = W / 2F - PAD;
        for (int r = 0; r < ROWS; r++) {
            int i = 1 + page * ROWS + r;
            if (i >= lines.size()) break;
            String[] parts = lines.get(i).split("\t", 3);
            if (parts.length < 3) continue;
            float y = 23 + r * 12;
            // the icon
            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.tryParse(parts[0]));
            if (item != null && item != net.minecraft.world.item.Items.AIR) {
                pose.pushPose();
                boolean block = item instanceof BlockItem;
                pose.translate((left + 4.5F) * S, -(y + 3.5F) * S, 0.004);
                float sz = (block ? 9 : 12) * S;
                pose.scale(sz, sz, block ? sz * 0.3F : 0.02F);
                items.renderStatic(new ItemStack(item), ItemDisplayContext.FIXED, lit, overlay, pose, buffers, be.getLevel(), 0);
                pose.popPose();
            }
            pose.pushPose();
            pose.scale(S, -S, S);
            String price = parts[2];
            float pw = font.width(price);
            float nameX = left + 12, nameRoom = right - pw - 5 - nameX;
            String name = parts[1];
            if (font.width(name) > nameRoom) name = font.plainSubstrByWidth(name, (int) (nameRoom - font.width("…"))).stripTrailing() + "…";
            font.drawInBatch(name, nameX, y, 0xFFF0F0EC, false, pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, lit);
            font.drawInBatch(price, right - pw, y, 0xFFFFE38A, false, pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, lit);
            pose.popPose();
        }
        if (pages > 1) {
            pose.pushPose();
            pose.scale(S * 0.75F, -S * 0.75F, S);
            String pg = (page + 1) + "/" + pages;
            font.drawInBatch(pg, right / 0.75F - font.width(pg), (H - 10) / 0.75F, 0xA0E8E8E8, false, pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, lit);
            pose.popPose();
        }
        pose.popPose();
    }
}
