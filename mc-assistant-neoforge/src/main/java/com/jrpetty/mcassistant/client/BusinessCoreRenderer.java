package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.BusinessCoreBlockEntity;
import com.jrpetty.mcassistant.item.CoreItems;
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
 * [wpb] What floats over a Business Core (BusinessCoreBlockEntity): the business's name, open or closed (green or red), and
 * "Deputy in charge" or "For sale" in gold when it is so. Seen within a dozen blocks, facing whoever looks, like a name over
 * a head; its own light, so it reads at night.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class BusinessCoreRenderer implements BlockEntityRenderer<BusinessCoreBlockEntity> {

    static final double SEEN = 12.0;
    private final Font font;

    public BusinessCoreRenderer(BlockEntityRendererProvider.Context ctx) {
        this.font = ctx.getFont();
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(CoreItems.CORE_BE.get(), BusinessCoreRenderer::new);
    }

    @Override
    public void render(BusinessCoreBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (be.name().isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Vec3 at = Vec3.atCenterOf(be.getBlockPos());
        if (mc.player.position().distanceToSqr(at) > SEEN * SEEN) return;
        pose.pushPose();
        pose.translate(0.5, 1.7, 0.5);
        pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        float s = 0.018F;
        pose.scale(s, -s, s);
        Matrix4f m = pose.last().pose();
        int back = (int) (mc.options.getBackgroundOpacity(0.25F) * 255.0F) << 24;
        int y = be.note().isEmpty() ? 0 : -10;
        line(be.note(), 0xFFE0B040, y - 10, m, buffers, back);
        line(be.name(), 0xFFFFFFFF, y, m, buffers, back);
        line(be.status(), be.open() ? 0xFF7CD67C : 0xFFE07A6A, y + 10, m, buffers, back);
        pose.popPose();
    }

    private void line(String text, int colour, int y, Matrix4f m, MultiBufferSource buffers, int back) {
        if (text.isEmpty()) return;
        float x = -font.width(text) / 2.0F;
        font.drawInBatch(text, x, y, 0x30FFFFFF & colour | 0x30000000, false, m, buffers, Font.DisplayMode.SEE_THROUGH, back, LightTexture.FULL_BRIGHT);   // as a name over a head: seen through a wall, faintly
        font.drawInBatch(text, x, y, colour, false, m, buffers, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
    }

    @Override
    public boolean shouldRenderOffScreen(BusinessCoreBlockEntity be) {
        return true;
    }
}
