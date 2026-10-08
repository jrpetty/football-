package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.MilestoneBlock;
import com.jrpetty.mcassistant.block.MilestoneBlockEntity;
import com.jrpetty.mcassistant.item.OreSackItem;
import com.jrpetty.mcassistant.item.WindowBoxItem;
import com.jrpetty.mcassistant.item.WorkItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Locale;

/**
 * [workitems] The work items on the client: the milestone's lettering, cut into its faces as a sign's is written
 * (MilestoneRenderer); the window box in the hand showing the flower it was made with, and the ore sack bulging with ore
 * once there is some in it (item properties the item models read).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class WorkItemsClient {

    private WorkItemsClient() {}

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(WorkItems.MILESTONE_BE.get(), MilestoneRenderer::new);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ItemProperties.register(WorkItems.WINDOW_BOX_ITEM.get(), ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "flower"),
                (stack, level, entity, seed) -> (WindowBoxItem.flower(stack).ordinal() + 0.5F) / 16.0F);
            ItemProperties.register(WorkItems.ORE_SACK.get(), ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "filled"),
                (stack, level, entity, seed) -> OreSackItem.fullness(stack) >= 0.25F ? 1.0F : 0.0F);
        });
    }

    /**
     * The milestone's lettering: on the face to the road, each town the road leads to and how far, with the way it lies
     * ("ALDERTOR" over "← 120"); and on each end, the town that a traveller coming up to that end is heading for. Cut into
     * the stone: dark letters with a lit edge under them, each line made to fit the stone's width.
     */
    public static class MilestoneRenderer implements BlockEntityRenderer<MilestoneBlockEntity> {

        /** The face's width and its height, in blocks; the face's offset from the middle of the cell. */
        private static final float FRONT_W = 0.56F, FRONT_H = 0.56F, FRONT_Z = 12.0F / 16.0F - 0.5F;
        private static final float SIDE_W = 0.44F, SIDE_X = 13.0F / 16.0F - 0.5F;
        /** A sign's lettering is this size. */
        private static final float SIGN = 1.0F / 96.0F;
        private static final int CUT = 0xFF2B2A27, EDGE = 0xFFC4C4BB;

        private final Font font;

        public MilestoneRenderer(BlockEntityRendererProvider.Context ctx) {
            this.font = ctx.getFont();
        }

        @Override
        public void render(MilestoneBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
            List<MilestoneBlockEntity.Way> ways = be.ways();
            if (ways.isEmpty() || !(be.getBlockState().getBlock() instanceof MilestoneBlock)) return;
            Direction facing = be.getBlockState().getValue(MilestoneBlock.FACING);
            Direction right = facing.getCounterClockWise();
            // The face to the road: each way in two lines, its name over its distance, the arrow the way it lies.
            String[] lines = new String[Math.min(4, ways.size() * 2)];
            for (int i = 0; i < lines.length / 2; i++) {
                MilestoneBlockEntity.Way w = ways.get(i);
                boolean toRight = w.dx() * right.getStepX() + w.dz() * right.getStepZ() > 0;
                lines[2 * i] = w.name().toUpperCase(Locale.ROOT);
                lines[2 * i + 1] = toRight ? w.far() + " →" : "← " + w.far();
            }
            pose.pushPose();
            pose.translate(0.5, 0.0, 0.5);
            pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
            face(pose, buffers, light, lines, FRONT_Z, FRONT_W, FRONT_H, 7.0F / 16.0F);
            // Each end: the town a traveller coming up to it is making for (the way that runs on past the stone).
            for (MilestoneBlockEntity.Way w : ways.subList(0, Math.min(2, ways.size()))) {
                int along = w.dx() * right.getStepX() + w.dz() * right.getStepZ();
                if (along == 0) continue;
                pose.pushPose();
                // Coming up from the right-hand end, heading left: the end on the right faces it.
                pose.mulPose(Axis.YP.rotationDegrees(along < 0 ? 90.0F : -90.0F));
                face(pose, buffers, light, new String[]{ w.name().toUpperCase(Locale.ROOT), String.valueOf(w.far()) }, SIDE_X, SIDE_W, 0.5F, 7.0F / 16.0F);
                pose.popPose();
            }
            pose.popPose();
        }

        /** Lines cut into a face this far out from the middle, centred on it, each made to fit its width. */
        private void face(PoseStack pose, MultiBufferSource buffers, int light, String[] lines, float out, float width, float height, float middle) {
            int n = 0;
            for (String l : lines) if (l != null) n++;
            if (n == 0) return;
            float lineH = Math.min(SIGN * 11.0F, height / n);
            float top = middle + lineH * n / 2.0F;
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i];
                if (l == null) continue;
                int w = font.width(l);
                float s = Math.min(SIGN, width / Math.max(1, w));
                s = Math.min(s, lineH / 10.0F);
                pose.pushPose();
                pose.translate(0.0F, top - lineH * i - (lineH - 8.0F * s) / 2.0F, out + 0.004F);
                pose.scale(s, -s, s);
                Matrix4f m = pose.last().pose();
                float x = -w / 2.0F;
                font.drawInBatch(l, x + 0.6F, 0.6F, EDGE, false, m, buffers, Font.DisplayMode.POLYGON_OFFSET, 0, light);
                pose.translate(0.0F, 0.0F, 0.0015F / s);
                font.drawInBatch(l, x, 0.0F, CUT, false, pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, light);
                pose.popPose();
            }
        }
    }
}
