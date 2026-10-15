package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.item.DiningItems;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * [dining] The client's side of eating out: a dish with no model of its own drawn on its plate as itself
 * (PlaceSettingRenderer), and a chair's seat that is not drawn at all.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class DiningClient {

    private DiningClient() {}

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(DiningItems.PLACE_SETTING_BE.get(), PlaceSettingRenderer::new);
        event.registerEntityRenderer(DiningItems.SEAT.get(), NoopRenderer::new);
    }
}
