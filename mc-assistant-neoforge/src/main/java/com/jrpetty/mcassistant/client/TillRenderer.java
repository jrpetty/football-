package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.TillBlockEntity;
import com.jrpetty.mcassistant.item.TillItems;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.joml.Matrix4f;

/**
 * [till] The little sign over a till (TillBlockEntity): the business's name, who's at it (green) or that nobody is
 * (amber), and for a few seconds after a sale "Serving: Hazel" in gold. Seen within eight blocks, facing whoever looks.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class TillRenderer implements BlockEntityRenderer<TillBlockEntity> {

    static final double SEEN = 8.0;
    private final Font font;

    public TillRenderer(BlockEntityRendererProvider.Context ctx) {
        this.font = ctx.getFont();
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(TillItems.TILL_BE.get(), TillRenderer::new);
        event.registerBlockEntityRenderer(TillItems.FITTING_BE.get(), FittingRenderer::new);
    }

    @Override
    public void render(TillBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (be.name().isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || be.getLevel() == null) return;
        Vec3 at = Vec3.atCenterOf(be.getBlockPos());
        if (mc.player.position().distanceToSqr(at) > SEEN * SEEN) return;
        boolean serving = !be.serving().isEmpty() && be.getLevel().getGameTime() < be.servingUntil();
        pose.pushPose();
        pose.translate(0.5, be.kiosk() ? 1.55 : 1.1, 0.5);
        pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        float s = 0.014F;
        pose.scale(s, -s, s);
        Matrix4f m = pose.last().pose();
        int back = (int) (mc.options.getBackgroundOpacity(0.25F) * 255.0F) << 24;
        int y = serving ? -10 : 0;
        if (serving) line("Serving: " + be.serving(), 0xFFE8C04A, y - 10, m, buffers, back);
        line(be.name(), 0xFFFFFFFF, y, m, buffers, back);
        line(be.status(), be.open() ? 0xFF7CD67C : 0xFFE0A85A, y + 10, m, buffers, back);
        pose.popPose();
    }

    private void line(String text, int colour, int y, Matrix4f m, MultiBufferSource buffers, int back) {
        if (text.isEmpty()) return;
        float x = -font.width(text) / 2.0F;
        font.drawInBatch(text, x, y, colour, false, m, buffers, Font.DisplayMode.NORMAL, back, LightTexture.FULL_BRIGHT);
    }

    @Override
    public boolean shouldRenderOffScreen(TillBlockEntity be) {
        return true;
    }
}
