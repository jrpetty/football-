package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** [police] The client's side of the watch: the model its police kit is drawn on (WatchModel, WatchLayer). */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class WatchClient {

    private WatchClient() {}

    @SubscribeEvent
    public static void onRegisterLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(WatchModel.LAYER, WatchModel::createBodyLayer);
    }
}
