package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Renders the assistant with the vanilla player model and the Steve skin. */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ClientSetup {

    private ClientSetup() {}

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(McAssistantMod.ASSISTANT.get(), AssistantRenderer::new);
    }

    public static class AssistantRenderer extends MobRenderer<AssistantEntity, HumanoidModel<AssistantEntity>> {
        private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png");

        public AssistantRenderer(EntityRendererProvider.Context context) {
            super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
        }

        @Override
        public ResourceLocation getTextureLocation(AssistantEntity entity) {
            return TEXTURE;
        }
    }
}
