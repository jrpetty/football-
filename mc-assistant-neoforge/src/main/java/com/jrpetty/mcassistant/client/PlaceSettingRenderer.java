package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.block.PlaceSettingBlock;
import com.jrpetty.mcassistant.block.PlaceSettingBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * [dining] A dish with no model of its own (PlaceSettingBlock.Dish.OTHER) drawn on its plate as the thing itself, lying
 * flat; smaller as it is eaten. Every other dish is the block's own model, and nothing is drawn here for it.
 */
public class PlaceSettingRenderer implements BlockEntityRenderer<PlaceSettingBlockEntity> {

    public PlaceSettingRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(PlaceSettingBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        BlockState st = be.getBlockState();
        if (!(st.getBlock() instanceof PlaceSettingBlock) || st.getValue(PlaceSettingBlock.DISH) != PlaceSettingBlock.Dish.OTHER) return;
        ItemStack food = be.food();
        if (food.isEmpty()) return;
        int stage = st.getValue(PlaceSettingBlock.BITES);
        float scale = 0.30F + 0.06F * stage;
        float lift = st.getValue(PlaceSettingBlock.CLOTH) ? 2.1F / 16 : 1.1F / 16;
        pose.pushPose();
        pose.translate(0.5, lift, 0.5);
        pose.mulPose(Axis.XP.rotationDegrees(90));
        pose.scale(scale, scale, scale);
        Minecraft.getInstance().getItemRenderer().renderStatic(food, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, pose, buffers,
            be.getLevel(), (int) be.getBlockPos().asLong());
        pose.popPose();
    }
}
