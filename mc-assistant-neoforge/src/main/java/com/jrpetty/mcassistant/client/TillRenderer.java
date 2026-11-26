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
    private final net.minecraft.client.renderer.entity.ItemRenderer items;

    public TillRenderer(BlockEntityRendererProvider.Context ctx) {
        this.font = ctx.getFont();
        this.items = ctx.getItemRenderer();
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(TillItems.TILL_BE.get(), TillRenderer::new);
        event.registerBlockEntityRenderer(TillItems.FITTING_BE.get(), FittingRenderer::new);
    }

    @Override
    public void render(TillBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (be.kiosk()) kiosk(be, pose, buffers, light, overlay);
        if (be.name().isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || be.getLevel() == null) return;
        Vec3 at = Vec3.atCenterOf(be.getBlockPos());
        if (mc.player.position().distanceToSqr(at) > SEEN * SEEN) return;
        boolean serving = !be.serving().isEmpty() && be.getLevel().getGameTime() < be.servingUntil();
        if (be.kiosk() && !serving) return;                                 // its own sign says what it is
        pose.pushPose();
        pose.translate(0.5, be.kiosk() ? 1.95 : 1.1, 0.5);
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

    /** Muted dye colours for a business's name on its kiosk's sign, picked by its key (the same business, the same colour). */
    private static final int[] COLOURS = { 0xFF7A2E22, 0xFF23426E, 0xFF2F5A2C, 0xFF5B2D5E, 0xFF7A5A12, 0xFF1F5F5F };

    /**
     * A kiosk: its wares in the cubbies under the desk (what it sells, drawn as they are), and its hanging sign, "Serve
     * yourself" in ink on both faces with the business's name under it in its colour.
     */
    private void kiosk(TillBlockEntity be, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.distanceToSqr(be.getBlockPos().getCenter()) > 32 * 32) return;
        net.minecraft.core.Direction face = be.getBlockState().getValue(com.jrpetty.mcassistant.block.TillBlock.FACING);
        pose.pushPose();
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-face.toYRot() + 180));
        pose.translate(-0.5, 0, -0.5);
        java.util.List<net.minecraft.world.item.ItemStack> shown = be.shown();
        double[] xs = { 12.25 / 16, 8 / 16.0, 3.75 / 16 };                     // the customer's left to right
        for (int i = 0; i < shown.size() && i < 3; i++) {
            net.minecraft.world.item.ItemStack st = shown.get(i);
            boolean block = st.getItem() instanceof net.minecraft.world.item.BlockItem;
            pose.pushPose();
            pose.translate(xs[i], 4.5 / 16 + (block ? 0.09 : 0.12), 4.3 / 16);
            float sc = block ? 0.2F : 0.3F;
            pose.scale(sc, sc, sc);
            items.renderStatic(st, net.minecraft.world.item.ItemDisplayContext.FIXED, light, overlay, pose, buffers, be.getLevel(), (int) be.getBlockPos().asLong() + i);
            pose.popPose();
        }
        int lit = LightTexture.pack(Math.max(LightTexture.block(light), 5), LightTexture.sky(light));
        String name = be.name();
        int colour = COLOURS[Math.floorMod(be.key().hashCode(), COLOURS.length)];
        for (int side = 0; side < 2; side++) {
            pose.pushPose();
            if (side == 0) {
                pose.translate(7.0 / 16, 21.6 / 16, 13.25 / 16 - 0.002);
                pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180));
            } else {
                pose.translate(7.0 / 16, 21.6 / 16, 14.25 / 16 + 0.002);
            }
            sign(pose, buffers, lit, name, colour);
            pose.popPose();
        }
        pose.popPose();
    }

    /** The sign's lettering, inside its board (ten pixels wide, four high). */
    private void sign(PoseStack pose, MultiBufferSource buffers, int lit, String name, int colour) {
        float width = 9.2F / 16, s1 = width / Math.max(1, font.width("Serve yourself"));
        s1 = Math.min(s1, 0.0085F);
        pose.pushPose();
        pose.scale(s1, -s1, s1);
        String t = "Serve yourself";
        font.drawInBatch(t, -font.width(t) / 2F, 2, 0xFF2B1D0E, false, pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, lit);
        pose.popPose();
        if (name.isEmpty()) return;
        float s2 = Math.min(0.0055F, width / Math.max(1, font.width(name)));
        pose.pushPose();
        pose.translate(0, -2.3 / 16, 0);
        pose.scale(s2, -s2, s2);
        font.drawInBatch(name, -font.width(name) / 2F, 0, colour, false, pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, lit);
        pose.popPose();
    }

    @Override
    public boolean shouldRenderOffScreen(TillBlockEntity be) {
        return true;
    }
}
