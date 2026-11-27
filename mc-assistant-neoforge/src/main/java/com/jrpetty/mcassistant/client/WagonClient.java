package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.WagonRegistry;
import com.jrpetty.mcassistant.item.WorkItems;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** [wagons] The trade wagon's renderer and its three models, and the crate a folk carries to a wagon. */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class WagonClient {

    private WagonClient() {}

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(WagonRegistry.TRADE_WAGON.get(), TradeWagonRenderer::new);
    }

    @SubscribeEvent
    public static void layers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(TradeWagonModel.HANDCART, TradeWagonModel::handcart);
        event.registerLayerDefinition(TradeWagonModel.WAGON, TradeWagonModel::wagon);
        event.registerLayerDefinition(TradeWagonModel.COVERED, TradeWagonModel::covered);
    }

    /** A shipping crate held out in front, in both hands, while the folk carries it to or from a wagon. */
    public static final class CrateLayer extends RenderLayer<VillageFolkEntity, FolkModel> {

        private final BlockRenderDispatcher blocks;

        public CrateLayer(RenderLayerParent<VillageFolkEntity, FolkModel> parent, BlockRenderDispatcher blocks) {
            super(parent);
            this.blocks = blocks;
        }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffers, int light, VillageFolkEntity folk, float limbSwing,
                           float limbSwingAmount, float pt, float age, float headYaw, float headPitch) {
            if (!WagonClientHooks.isCarrying(folk.getId())) return;
            BlockState crate = WorkItems.SHIPPING_CRATE.get().defaultBlockState();
            pose.pushPose();
            // The model's space is upside down (y down, face to -z): the crate at the hands, out in front of the chest.
            pose.scale(-1.0F, -1.0F, 1.0F);
            pose.translate(-0.25F, -0.95F, -0.78F);
            pose.scale(0.5F, 0.5F, 0.5F);
            blocks.renderSingleBlock(crate, pose, buffers, light, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
    }
}
