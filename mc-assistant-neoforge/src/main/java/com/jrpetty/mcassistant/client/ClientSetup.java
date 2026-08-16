package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * Client wiring: render the assistant as a Steve-skinned player, WITH its worn
 * armor and held items visible, and hook up the management screen.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ClientSetup {

    private ClientSetup() {}

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(McAssistantMod.ASSISTANT.get(), AssistantRenderer::new);
    }

    @SubscribeEvent
    public static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(McAssistantMod.ASSISTANT_MENU.get(), AssistantScreen::new);
    }

    @SubscribeEvent
    public static void onRegisterKeys(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        event.register(com.jrpetty.mcassistant.client.voice.VoiceInput.TALK);
        event.register(AssistantTargeting.ORDERS);
        event.register(AssistantTargeting.ROSTER);
    }

    public static class AssistantRenderer extends MobRenderer<AssistantEntity, HumanoidModel<AssistantEntity>> {
        private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png");

        public AssistantRenderer(EntityRendererProvider.Context context) {
            super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
            // Worn armor.
            this.addLayer(new HumanoidArmorLayer<>(
                this,
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.getModelManager()));
            // Held item(s).
            this.addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
        }

        @Override
        public ResourceLocation getTextureLocation(AssistantEntity entity) {
            return TEXTURE;
        }
    }
}
